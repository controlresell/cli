#ifndef CONTROLRESELL_WINDOWS_SUPPORT_H
#define CONTROLRESELL_WINDOWS_SUPPORT_H
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#include <windows.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <aclapi.h>
#include <sddl.h>
#include <shellapi.h>
#include <stdlib.h>
#include <wchar.h>

static inline wchar_t **controlresell_windows_arguments(int *count) { return CommandLineToArgvW(GetCommandLineW(), count); }

static inline int controlresell_windows_random(void *buffer, unsigned long count) {
    return BCryptGenRandom(NULL, (PUCHAR)buffer, count, BCRYPT_USE_SYSTEM_PREFERRED_RNG) >= 0;
}

/* Caller frees the returned LocalAlloc SID string. Never expose token credentials. */
static inline wchar_t *controlresell_windows_user_sid(void) {
    HANDLE token = NULL;
    DWORD size = 0;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return NULL;
    GetTokenInformation(token, TokenUser, NULL, 0, &size);
    TOKEN_USER *user = (TOKEN_USER *)malloc(size);
    wchar_t *sid = NULL;
    if (user && GetTokenInformation(token, TokenUser, user, size, &size))
        ConvertSidToStringSidW(user->User.Sid, &sid);
    free(user);
    CloseHandle(token);
    return sid;
}

static inline int controlresell_windows_owner_matches(const wchar_t *path, PSID expected) {
    PSECURITY_DESCRIPTOR descriptor = NULL;
    PSID owner = NULL;
    DWORD result = GetNamedSecurityInfoW((wchar_t *)path, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION,
        &owner, NULL, NULL, NULL, &descriptor);
    int matches = result == ERROR_SUCCESS && owner && EqualSid(owner, expected);
    if (descriptor) LocalFree(descriptor);
    return matches;
}

/* An elevated token may default new-file ownership to Administrators, not its user SID. */
static inline PSECURITY_DESCRIPTOR controlresell_windows_private_descriptor(void) {
    wchar_t *sid = controlresell_windows_user_sid();
    if (!sid) return NULL;
    size_t capacity = 64 + 2 * wcslen(sid);
    wchar_t *sddl = (wchar_t *)calloc(capacity, sizeof(wchar_t));
    PSECURITY_DESCRIPTOR descriptor = NULL;
    if (sddl) {
        swprintf(sddl, capacity, L"O:%lsD:P(A;OICI;FA;;;%ls)", sid, sid);
        if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &descriptor, NULL)) descriptor = NULL;
    }
    free(sddl);
    LocalFree(sid);
    return descriptor;
}

/* Protected DACL with one explicit allow entry for the current user, on both directory and file. */
static inline int controlresell_windows_write_private(const wchar_t *path, const wchar_t *parent,
        const wchar_t *temporary, const void *bytes, unsigned long length, int replace_existing) {
    PSECURITY_DESCRIPTOR descriptor = controlresell_windows_private_descriptor();
    if (!descriptor) return 2;
    int success = 0;
    HANDLE file = INVALID_HANDLE_VALUE;
    int created = 0;
    int stage = 2;
    SECURITY_ATTRIBUTES attributes = { sizeof(SECURITY_ATTRIBUTES), descriptor, FALSE };
    PSID owner = NULL;
    PACL dacl = NULL;
    BOOL defaulted = FALSE, present = FALSE;
    if (!GetSecurityDescriptorOwner(descriptor, &owner, &defaulted) ||
        !GetSecurityDescriptorDacl(descriptor, &present, &dacl, &defaulted) || !present || !dacl) goto cleanup;
    stage = 3;
    if (!CreateDirectoryW(parent, &attributes)) {
        DWORD error = GetLastError();
        if (error != ERROR_ALREADY_EXISTS) { stage = 30000 + (int)error; goto cleanup; }
    }
    stage = 4;
    DWORD parent_flags = GetFileAttributesW(parent);
    if (parent_flags == INVALID_FILE_ATTRIBUTES || !(parent_flags & FILE_ATTRIBUTE_DIRECTORY) ||
        (parent_flags & FILE_ATTRIBUTE_REPARSE_POINT) || !controlresell_windows_owner_matches(parent, owner)) goto cleanup;
    stage = 5;
    if (SetNamedSecurityInfoW((wchar_t *)parent, SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, NULL, NULL, dacl, NULL) != ERROR_SUCCESS) goto cleanup;
    DWORD destination_flags = GetFileAttributesW(path);
    stage = 6;
    if (destination_flags != INVALID_FILE_ATTRIBUTES &&
        ((destination_flags & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY)) || !controlresell_windows_owner_matches(path, owner))) goto cleanup;
    stage = 7;
    file = CreateFileW(temporary, GENERIC_WRITE, 0, &attributes, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) goto cleanup;
    created = 1;
    stage = 8;
    DWORD offset = 0;
    while (offset < length) {
        DWORD written = 0;
        if (!WriteFile(file, (const char *)bytes + offset, length - offset, &written, NULL) || written == 0) goto cleanup;
        offset += written;
    }
    stage = 9;
    if (!FlushFileBuffers(file)) goto cleanup;
    CloseHandle(file);
    file = INVALID_HANDLE_VALUE;
    stage = 10;
    for (int attempt = 0; attempt < 20; attempt++) {
        DWORD flags = MOVEFILE_WRITE_THROUGH | (replace_existing ? MOVEFILE_REPLACE_EXISTING : 0);
        if (MoveFileExW(temporary, path, flags)) { success = 1; break; }
        DWORD error = GetLastError();
        if (!replace_existing && (error == ERROR_ALREADY_EXISTS || error == ERROR_FILE_EXISTS)) { stage = -1; goto cleanup; }
        if (error != ERROR_SHARING_VIOLATION && error != ERROR_ACCESS_DENIED) break;
        Sleep(25); /* A concurrent token read may briefly hold a non-delete-sharing handle. */
    }
cleanup:
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    if (created && !success) DeleteFileW(temporary);
    if (descriptor) LocalFree(descriptor);
    return success ? 0 : stage;
}

#endif
