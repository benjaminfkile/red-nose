package com.wmsfo.rednose.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// red-nose.md 9.1, contracts 8.5: each flavour admits only its own API origin.
class ApiFlavourTest {

    private val prod = "https://api.example.org"

    @Test fun origin_is_case_insensitive_for_scheme_and_host() {
        assertEquals("https://api.example.org", ApiFlavour.origin("HTTPS://API.Example.ORG"))
    }

    @Test fun origin_drops_a_default_port_and_keeps_another() {
        assertEquals("https://api.example.org", ApiFlavour.origin("https://api.example.org:443"))
        assertEquals("http://api.example.org", ApiFlavour.origin("http://api.example.org:80"))
        assertEquals("https://api.example.org:8443", ApiFlavour.origin("https://api.example.org:8443"))
        assertEquals("http://api.example.org:5000", ApiFlavour.origin("http://api.example.org:5000"))
    }

    @Test fun origin_ignores_a_trailing_slash_and_a_path() {
        assertEquals("https://api.example.org", ApiFlavour.origin("https://api.example.org/"))
        assertEquals("https://api.example.org", ApiFlavour.origin("https://api.example.org/v1/x?q=1#f"))
    }

    @Test fun origin_is_null_for_a_malformed_string() {
        assertNull(ApiFlavour.origin("api.example.org"))
        assertNull(ApiFlavour.origin("https://"))
        assertNull(ApiFlavour.origin("https://host:abc"))
    }

    @Test fun prod_admits_only_the_prod_origin() {
        assertTrue(ApiFlavour.admits("https://api.example.org/", "prod", prod))
        assertFalse(ApiFlavour.admits("https://api-dev.example.org", "prod", prod))
        assertFalse(ApiFlavour.admits("http://api.example.org", "prod", prod))
    }

    @Test fun dev_admits_every_origin_but_the_prod_one() {
        assertFalse(ApiFlavour.admits("https://api.example.org", "dev", prod))
        assertTrue(ApiFlavour.admits("https://api-dev.example.org", "dev", prod))
        assertTrue(ApiFlavour.admits("http://lan.example.org:5000", "dev", prod))
    }

    @Test fun a_malformed_api_is_never_admitted() {
        assertFalse(ApiFlavour.admits("api.example.org", "prod", prod))
        assertFalse(ApiFlavour.admits("api.example.org", "dev", prod))
        assertFalse(ApiFlavour.admits("https://host:abc", "dev", prod))
    }
}
