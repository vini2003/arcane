package dev.omega.arcane.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static dev.omega.arcane.tooling.MolangCursorContext.Kind.*;

public class MolangCursorContextTests {

    @Test
    public void ContextAt_PartialMember_NamespaceAndPrefix() {
        var context = at("q.wor|");

        Assertions.assertEquals(MEMBER, context.kind());
        Assertions.assertEquals(MolangNamespace.QUERY, context.namespace());
        Assertions.assertEquals("q", context.namespaceText());
        Assertions.assertEquals("wor", context.prefix());
        Assertions.assertEquals(2, context.replaceStart());
        Assertions.assertEquals(5, context.replaceEnd());
    }

    @Test
    public void ContextAt_RightAfterDot_EmptyMember() {
        var context = at("1 + query.|");

        Assertions.assertEquals(MEMBER, context.kind());
        Assertions.assertEquals("query", context.namespaceText());
        Assertions.assertEquals("", context.prefix());
        Assertions.assertEquals(10, context.replaceStart());
        Assertions.assertEquals(10, context.replaceEnd());
    }

    @Test
    public void ContextAt_InsideMember_ReplacesTheWholeName() {
        var context = at("v.ab|cd + 1");

        Assertions.assertEquals(MolangNamespace.VARIABLE, context.namespace());
        Assertions.assertEquals("ab", context.prefix());
        Assertions.assertEquals(2, context.replaceStart());
        Assertions.assertEquals(6, context.replaceEnd());
        Assertions.assertEquals(6, at("q.|abcd").replaceEnd());
    }

    @Test
    public void ContextAt_BareName_Identifier() {
        var context = at("1 + que|");

        Assertions.assertEquals(IDENTIFIER, context.kind());
        Assertions.assertNull(context.namespace());
        Assertions.assertEquals("que", context.prefix());
        Assertions.assertEquals(4, context.replaceStart());
        Assertions.assertEquals(7, context.replaceEnd());
    }

    @Test
    public void ContextAt_AfterOperatorOrAtStart_EmptyIdentifier() {
        Assertions.assertEquals(IDENTIFIER, at("1 + |").kind());
        Assertions.assertEquals("", at("1 + |").prefix());
        Assertions.assertEquals(IDENTIFIER, at("|").kind());
        Assertions.assertEquals(IDENTIFIER, at("(|").kind());
    }

    @Test
    public void ContextAt_NumbersAndStrings_None() {
        Assertions.assertEquals(NONE, at("12|").kind());
        Assertions.assertEquals(NONE, at("1.|").kind());
        Assertions.assertEquals(NONE, at("\"ab|").kind());
        Assertions.assertEquals(NONE, at("q.a.|").kind());
    }

    @Test
    public void ContextAt_InsideCall_ArgumentIndex() {
        var call = at("math.clamp(q.a, |").call();

        Assertions.assertNotNull(call);
        Assertions.assertEquals(MolangNamespace.MATH, call.namespace());
        Assertions.assertEquals("clamp", call.name());
        Assertions.assertEquals(1, call.argument());
        Assertions.assertEquals(10, call.openParen());
    }

    @Test
    public void ContextAt_GroupingInsideCall_SkipsTheGroup() {
        var call = at("math.clamp(q.a, (1 + |").call();

        Assertions.assertNotNull(call);
        Assertions.assertEquals("clamp", call.name());
        Assertions.assertEquals(1, call.argument());
        Assertions.assertEquals(0, at("math.max((1, 2|").call().argument());
    }

    @Test
    public void ContextAt_AfterClosedCall_NoCall() {
        Assertions.assertNull(at("math.sin(1) + |").call());
        Assertions.assertNull(at("(1 + |").call());
    }

    @Test
    public void ContextAt_OutOfRangeOffset_Clamped() {
        Assertions.assertEquals("ab", MolangTooling.contextAt("q.ab", 99).prefix());
        Assertions.assertEquals(IDENTIFIER, MolangTooling.contextAt(null, -3).kind());
    }

    private static MolangCursorContext at(String marked) {
        int caret = marked.indexOf('|');
        return MolangTooling.contextAt(marked.substring(0, caret) + marked.substring(caret + 1), caret);
    }
}
