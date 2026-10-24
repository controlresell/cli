package com.controlresell.cli

import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogsCommandsTest {
    private fun signedIn() = CliHarness().apply { signIn() }

    @Test fun `catalogs brands prints a table, sends the query and gives the next cursor`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/catalogs/brands",
            body = """{"data":[{"id":1,"name":"Nike","custom":false},{"id":2,"name":"Nikon","custom":true}],"nextCursor":"c2"}""")
        val result = cli.run("catalogs", "brands", "--q", "nik", "--limit", "2")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Nike")
        assertContains(result.stdout, "Nikon")
        assertEquals("Next page: run the same command with --cursor c2\n", result.stderr)
        cli.requests.single().url.parameters.let { assertEquals("nik", it["q"]); assertEquals("2", it["limit"]) }
    }

    @Test fun `catalogs colors --all follows the cursors and sends the category, and an empty list says so`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/catalogs/colors", body = """{"data":[{"id":3,"key":"red","title":"Red"}],"nextCursor":"p2"}""",
            query = mapOf("cursor" to null))
        cli.on(HttpMethod.Get, "/api/public/v1/catalogs/colors", body = """{"data":[{"id":4,"key":"blue","title":"Blue"}],"nextCursor":null}""",
            query = mapOf("cursor" to "p2"))
        val json = cli.run("catalogs", "colors", "--category", "12", "--all", "--json")
        assertEquals(0, json.code, json.stderr)
        assertContains(json.stdout, "red"); assertContains(json.stdout, "blue")
        assertTrue(cli.requests.all { it.url.parameters["categoryId"] == "12" })
        val empty = signedIn()
        empty.on(HttpMethod.Get, "/api/public/v1/catalogs/materials", body = """{"data":[],"nextCursor":null}""")
        assertEquals("No materials.\n", empty.run("catalogs", "materials").stdout)
    }

    @Test fun `catalogs categories sends the parent and the platform`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/catalogs/categories",
            body = """{"data":[{"id":5,"parentId":2,"title":"Jackets","hasChildren":false,"sizeGroupIds":[1],"platforms":["VINTED"]}],"nextCursor":null}""")
        val result = cli.run("catalogs", "categories", "--parent", "2", "--platform", "VINTED")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Jackets")
        cli.requests.single().url.parameters.let { assertEquals("2", it["parentId"]); assertEquals("VINTED", it["platform"]) }
    }

    @Test fun `catalogs fields prints the fields of a category, and a missing category exits with 4`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/catalogs/categories/5/fields", body = """{"categoryId":5,"sizeGroupIds":[1,2],
            "enumFields":[{"key":"fit","target":"enumValues","multiple":false,"platform":"VINTED","values":[{"id":9,"key":"slim","title":"Slim"}]}],
            "textFields":[{"key":"model","type":"STRING","required":true}],"measurements":[{"target":"measurementLength","unit":"cm","required":false}]}""")
        cli.on(HttpMethod.Get, "/api/public/v1/catalogs/categories/6/fields", status = 404,
            body = CliHarness.error("category_not_found", "No such category."))
        val result = cli.run("catalogs", "fields", "5")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "fit -> enumValues")
        assertContains(result.stdout, "9=slim")
        assertContains(result.stdout, "model (STRING, required)")
        assertContains(result.stdout, "measurementLength (cm)")
        assertEquals(ExitCode.NOT_FOUND, cli.run("catalogs", "fields", "6").code)
    }

    @Test fun `catalogs category prints a sheet`() = runTest {
        val cli = signedIn()
        cli.on(HttpMethod.Get, "/api/public/v1/catalogs/categories/5",
            body = """{"id":5,"parentId":2,"title":"Jackets","hasChildren":false,"sizeGroupIds":[1],"platforms":["VINTED"]}""")
        val result = cli.run("catalogs", "category", "5")
        assertEquals(0, result.code, result.stderr)
        assertContains(result.stdout, "Jackets")
        assertContains(result.stdout, "VINTED")
    }
}
