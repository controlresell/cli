#!/usr/bin/env python3
"""A local stand-in for the ControlResell public API, for the native smoke tests. Loopback HTTP only.

Usage: python3 scripts/fake-api.py --port PORT --key API_KEY --log FILE
       python3 scripts/fake-api.py --dump   (print every payload it serves as one JSON object and exit; the JVM test
                                             SmokeFakeApiContractTest decodes them with the pinned Kotlin SDK models)
Every request is appended to the log as one JSON line (method, path, query, headers).
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

PREFIX = '/api/public/v1'
ME = {'id': 42, 'username': 'smoke-seller', 'currency': 'EUR'}
ACCOUNTS = [{'id': f'acc_{n}', 'platform': 'vinted', 'name': f'Account {n}', 'status': 'CONNECTED'} for n in range(1, 4)]
# The nested lists are not empty so that the types inside them (ItemPhoto, ItemEnumValue, ItemAttribute,
# ItemCustomField) are decoded by the contract test too.
ITEMS = [{'id': n, 'name': f'Item {n}', 'photos': [{'id': f'photo_{n}', 'index': 0, 'url': f'https://example.com/photos/{n}.jpg'}],
          'colorIds': [1], 'labels': ['smoke'], 'sold': n % 2 == 0,
          'materialIds': [2], 'enumValues': [{'enumKey': 'season', 'enumValue': 'SUMMER'}],
          'attributes': [{'fieldKey': 'brand_note', 'value': 'vintage'}], 'customFields': [{'fieldId': 7, 'value': 'shelf A'}],
          'createdAt': '2026-10-01T10:00:00Z', 'updatedAt': '2026-10-02T10:00:00Z',
          'sellingPrice': {'amount': f'{n}.50', 'currency': 'EUR'}} for n in range(1, 6)]
LISTINGS = [{'id': 'lst_1', 'itemId': 1, 'accountId': 'acc_1', 'platformId': '9001', 'url': 'https://example.com/items/9001',
             'price': {'amount': '1.50', 'currency': 'EUR'}, 'status': 'ONLINE',
             'publishedAt': '2026-10-01T11:00:00Z', 'updatedAt': '2026-10-01T11:00:00Z'}]
CONVERSATIONS = [{'id': f'conv_{n}', 'accountId': 'acc_1', 'unread': n == 1, 'pinned': False,
                  'updatedAt': '2026-10-03T09:00:00Z', 'buyerLogin': f'buyer{n}'} for n in range(1, 3)]
MESSAGES = [{'id': f'msg_{n}', 'conversationId': 'conv_1', 'author': 'BUYER', 'type': 'MESSAGE', 'status': 'SENT',
             'createdAt': '2026-10-03T09:00:00Z', 'body': f'Hello {n}'} for n in range(1, 4)]


def error(status, code, message):
    return status, {'error': {'code': code, 'message': message, 'params': {}, 'requestId': 'req_smoke'}}


def page(elements, query):
    limit = int(query.get('limit', ['50'])[0])
    start = int(query.get('cursor', ['0'])[0])
    chunk = elements[start:start + limit]
    following = start + limit
    return 200, {'data': chunk, 'nextCursor': str(following) if following < len(elements) else None}


def route(path, query):
    """The answer (status, body) to an authenticated GET; shared by the server and by --dump."""
    if not path.startswith(PREFIX):
        return error(404, 'route_not_found', 'No such route.')
    path = path[len(PREFIX):]
    if path == '/me':
        return 200, ME
    if path == '/accounts':
        return page(ACCOUNTS, query)
    if path.startswith('/accounts/'):
        found = [a for a in ACCOUNTS if a['id'] == path.split('/')[2]]
        return (200, found[0]) if found else error(404, 'account_not_found', 'No such account.')
    if path == '/items':
        selected = ITEMS
        if 'sold' in query:
            selected = [i for i in selected if str(i['sold']).lower() == query['sold'][0]]
        return page(selected, query)
    if path == '/items/429':
        return error(429, 'rate_limited', 'Too many requests.')
    if path == '/items/402':
        return error(402, 'plan_required', 'Your plan does not include the API.')
    if path.endswith('/listings'):
        return 200, {'data': LISTINGS, 'nextCursor': None}
    if path.startswith('/items/'):
        found = [i for i in ITEMS if str(i['id']) == path.split('/')[2]]
        return (200, found[0]) if found else error(404, 'item_not_found', 'No such item.')
    if path == '/conversations':
        return page(CONVERSATIONS, query)
    if path.endswith('/messages'):
        return page(MESSAGES, query)
    if path.startswith('/conversations/'):
        found = [c for c in CONVERSATIONS if c['id'] == path.split('/')[2]]
        return (200, found[0]) if found else error(404, 'conversation_not_found', 'No such conversation.')
    return error(404, 'route_not_found', 'No such route.')


def snapshot():
    """Every payload the smoke reads, by name, taken from route() itself so the dump cannot drift from the server."""
    def get(path):
        status, body = route(PREFIX + path, {})
        assert status == 200, (path, status)
        return body
    return {
        'me': get('/me'),
        'accountPage': get('/accounts'),
        'account': get('/accounts/acc_1'),
        'itemPage': get('/items'),
        'item': get('/items/1'),
        'listingPage': get('/items/1/listings'),
        'conversationPage': get('/conversations'),
        'conversation': get('/conversations/conv_1'),
        'messagePage': get('/conversations/conv_1/messages'),
        'errors': [route(PREFIX + path, {})[1] for path in ('/items/999', '/items/402', '/items/429', '/nowhere')]
                  + [error(401, 'invalid_token', 'The API key is invalid or revoked.')[1]],
    }


def handler(key, log):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            url = urlsplit(self.path)
            query = parse_qs(url.query)
            with open(log, 'a') as output:
                output.write(json.dumps({'method': 'GET', 'path': url.path, 'query': query, 'headers': dict(self.headers)}) + '\n')
            if self.headers.get('Authorization') != f'Bearer {key}':
                status, body = error(401, 'invalid_token', 'The API key is invalid or revoked.')
            else:
                status, body = route(url.path, query)
            payload = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            if status == 429:
                self.send_header('Retry-After', '7')
            self.end_headers()
            self.wfile.write(payload)
    return Handler


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--dump', action='store_true')
    args.add_argument('--port', type=int)
    args.add_argument('--key')
    args.add_argument('--log')
    options = args.parse_args()
    if options.dump:
        if options.port is not None or options.key or options.log:
            args.error('--dump takes no other option.')
        print(json.dumps(snapshot()))
        return
    if options.port is None or not options.key or not options.log:
        args.error('--port, --key and --log are required unless --dump is given.')
    ThreadingHTTPServer(('127.0.0.1', options.port), handler(options.key, options.log)).serve_forever()


if __name__ == '__main__':
    main()
