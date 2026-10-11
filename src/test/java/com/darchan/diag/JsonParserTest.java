package com.darchan.diag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The tiny JSON reader is strict about what it accepts: each rule is pinned by an accepting and a rejecting case. */
class JsonParserTest {

    private static IllegalArgumentException rejects(String text) {
        return assertThrows(IllegalArgumentException.class, () -> Json.parse(text), "should reject: " + text);
    }

    @Test
    void emptyContainersParseToEmptyContainers() {
        assertEquals(Map.of(), Json.parse("{}"));
        assertEquals(List.of(), Json.parse("[]"));
        assertEquals(Map.of(), Json.parse(" \t\r\n{ \n}\t "));
        assertEquals(List.of(), Json.parse(" [ ] "));
    }

    @Test
    void whitespaceIsAllowedBetweenAllTokens() {
        Object v = Json.parse(" { \"a\" : [ 1 , 2 ] , \"b\" : { \"c\" : true } } ");
        assertEquals(Map.of("a", List.of(1L, 2L), "b", Map.of("c", true)), v);
        assertEquals(List.of(1L, List.of(2L)), Json.parse("[ 1 , [ 2 ] ]"));
        assertEquals(List.of("x", false, true), Json.parse("[\n\"x\",\nfalse,\ntrue\n]"));
    }

    @Test
    void objectsKeepKeyOrderAndRejectDuplicates() {
        Map<?, ?> m = (Map<?, ?>) Json.parse("{\"z\":1,\"a\":2,\"m\":3}");
        assertEquals(List.of("z", "a", "m"), List.copyOf(m.keySet()));
        assertTrue(rejects("{\"a\":1,\"a\":2}").getMessage().contains("duplicate key a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "}", "[", "]", "{\"a\"", "{\"a\":", "{\"a\":1", "{\"a\":1,", "{\"a\":1,}", "{,}",
            "{1:2}", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "[1,]", "[,1]", "[1 2]", "[1", "[1,", "nul", "tru", "fals",
            "nulll", "True", "NULL", "'a'", "\"abc", "\"a\\", "\"a\\x\"", "1 2", "{} {}", "[] ,", "}{"})
    void malformedDocumentsAreRejected(String text) {
        rejects(text);
    }

    @Test
    void literalsParseToTheirValues() {
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
        assertEquals(Boolean.TRUE, Json.parse("  true  "));
        assertTrue(rejects("trux").getMessage().contains("expected true"));
        assertTrue(rejects("falsy").getMessage().contains("expected false"));
        assertTrue(rejects("nope").getMessage().contains("expected null"));
    }

    @Test
    void numbersAreIntegersOnly() {
        assertEquals(0L, Json.parse("0"));
        assertEquals(-0L, Json.parse("-0"));
        assertEquals(42L, Json.parse("42"));
        assertEquals(-17L, Json.parse("-17"));
        assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, Json.parse("-9223372036854775808"));
        rejects("9223372036854775808");
        rejects("-9223372036854775809");
        rejects("-");
        rejects("--1");
        rejects("1.5");
        rejects("1e3");
        rejects("1E3");
        rejects("0.0");
        assertTrue(rejects("2.5").getMessage().contains("only integer numbers"));
        assertTrue(rejects("-").getMessage().contains("bad number"));
        // a number ends at the first non-digit
        rejects("12abc");
        assertEquals(List.of(1L, 2L), Json.parse("[1,2]"));
        assertEquals(Map.of("n", -5L), Json.parse("{\"n\":-5}"));
    }

    @Test
    void stringsDecodeEscapes() {
        assertEquals("", Json.parse("\"\""));
        assertEquals("a\"b\\c/d", Json.parse("\"a\\\"b\\\\c\\/d\""));
        assertEquals("\b\f\n\r\t", Json.parse("\"\\b\\f\\n\\r\\t\""));
        assertEquals("A", Json.parse("\"\\u0041\""));
        assertEquals("\u00e9\u00E9", Json.parse("\"\\u00e9\\u00E9\""));
        assertEquals("\u0000\uffff", Json.parse("\"\\u0000\\uFFFF\""));
        assertEquals("x y", Json.parse("\"x y\""));
        assertEquals("\u20ac", Json.parse("\"\u20ac\""));
    }

    @Test
    void unicodeEscapesNeedExactlyFourAsciiHexDigits() {
        rejects("\"\\u\"");
        rejects("\"\\u1\"");
        rejects("\"\\u12\"");
        rejects("\"\\u123\"");
        rejects("\"\\u123");
        assertEquals("\u1234", Json.parse("\"\\u1234\""));
        // the characters just outside each hex range: '/' ':' '@' 'G' '`' 'g'
        for (char c : new char[]{'/', ':', '@', 'G', '`', 'g', ' ', '\u0663'}) {
            assertTrue(rejects("\"\\u000" + c + "\"").getMessage().contains("4 hex digits"), "char " + c);
        }
        // the characters at the edges of each range are accepted
        for (char c : new char[]{'0', '9', 'a', 'f', 'A', 'F'}) {
            assertEquals(1, ((String) Json.parse("\"\\u000" + c + "\"")).length(), "char " + c);
        }
        assertTrue(rejects("\"\\u00").getMessage().contains("truncated"));
        assertTrue(rejects("\"\\u004").getMessage().contains("truncated"));
        assertEquals("\u0041", Json.parse("\"\\u0041\""), "the escape may be the last thing before the closing quote");
    }

    @Test
    void rawControlCharactersInStringsAreRejectedButSpaceIsFine() {
        for (int c = 0; c < 0x20; c++) {
            rejects("\"a" + (char) c + "b\"");
        }
        assertEquals("a b", Json.parse("\"a b\""));
        assertEquals("a\u007fb", Json.parse("\"a\u007fb\""), "DEL is not a JSON control character");
        assertTrue(rejects("\"tab\there\"").getMessage().contains("control character"));
    }

    @Test
    void keysMustBeStringsAndTheColonIsRequired() {
        assertTrue(rejects("{a:1}").getMessage().contains("expected a string key"));
        assertTrue(rejects("{\"a\" 1}").getMessage().contains("expected ':'"));
        assertTrue(rejects("{\"a\":1 \"b\":2}").getMessage().contains("expected ',' or '}'"));
        assertTrue(rejects("[1 2]").getMessage().contains("expected ',' or ']'"));
        assertTrue(rejects("[1] x").getMessage().contains("trailing characters"));
        assertTrue(rejects("").getMessage().contains("unexpected end of input"));
        assertTrue(rejects("?").getMessage().contains("unexpected character '?'"));
    }

    @Test
    void errorsSayWhereTheyHappened() {
        assertEquals("JSON error at offset 5: expected ':'", rejects("{\"a\" 1}").getMessage());
        assertTrue(rejects("[1, 2, ?]").getMessage().startsWith("JSON error at offset 7:"));
    }

    @Test
    void quoteEscapesWhatMustBeEscaped() {
        assertEquals("\"\"", Json.quote(""));
        assertEquals("\"a\\\"b\\\\c\"", Json.quote("a\"b\\c"));
        assertEquals("\"\\n\\r\\t\"", Json.quote("\n\r\t"));
        assertEquals("\"\\u0001\\u001f\"", Json.quote("\u0001\u001f"));
        assertEquals("\"\\u0000\"", Json.quote("\u0000"));
        assertEquals("\"\\u0008\\u000c\"", Json.quote("\b\f"), "backspace and form feed use \\u escapes");
        assertEquals("\" \u007f\u00e9\u20ac\"", Json.quote(" \u007f\u00e9\u20ac"), "everything else is written as is");
        assertEquals("\"/\"", Json.quote("/"));
    }
}
