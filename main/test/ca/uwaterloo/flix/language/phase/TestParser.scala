package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.language.errors.{LexerError, ParseError, WeederError}
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

import org.scalatest.Suites

class TestParser extends Suites(
  new TestParserRecovery,
  new TestParserHappy,
  new TestParserSad,
)

/**
  * Recover tests are cases where we would like to check that the parser recovers from a syntax error.
  * Each test contains one or more syntax error.
  * The parser should understand all the surrounding code but still produce a parse error for the syntax mistake.
  * That is asserted by checking that main is defined in the compilation result.
  * There is an theoretically infinite amount of possible syntax errors (we could just start fuzzing random strings),
  * so these test cover some "sensible amount" of broad errors.
  * Some areas that could use more test are:
  *   - Declarations other than definitions (module, enum, trait, signature, effect).
  *   - Patterns
  *   - Map, Set, List, Vector and Array literals.
  *   - "Niche" expressions (OpenAs, JVMops, Fixpoint expressions).
  *
  * Note that these tests use [[check]] rather than [[compile]].
  * That's because a compile converts any check failure into a [[ca.uwaterloo.flix.util.Validation.Failure]] before running, codegen so the result we would like to expect is lost.
  */
class TestParserRecovery extends AnyFunSuite with TestUtils {

  test("Use.01") {
    val input =
      """
        |use Color.{Red;
        |enum Color { case Red }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("Use.02") {
    val input =
      """
        |use Color.{Red =>
        |enum Color { case Red }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("Use.03") {
    val input =
      """
        |use Color.{Red => ScarletRed;
        |enum Color { case Red }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("Import.01") {
    val input =
      """
        |import java.lang.{StringBuffer,
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("Import.02") {
    val input =
      """
        |import java.lang.{StringBuffer => StrBuf
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("Import.03") {
    val input =
      """
        |import java.lang.{StringBuffer, , CharSequence};
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("Parameters.01") {
    val input =
      """
        |def foo(x: Int32, , z: Int32): Int32 = ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("Parameters.02") {
    val input =
      """
        |def foo(x: Int32,
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("NoDefBody.01") {
    val input =
      """
        |def foo(x: Int32): Int32
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("NoDefType.01") {
    val input =
      """
        |def foo(x: Int32) = ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("LeadOnCurlyR.01") {
    val input =
      """
        |} def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("DanglingDocComment.01") {
    val input =
      """
        |def main(): Unit = ()
        |/// This documents nothing
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("DanglingDocComment.02") {
    val input =
      """
        |mod Foo {
        |  /// This documents nothing
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("DanglingDocComment.03") {
    val input =
      """
        |trait Foo[t] {
        |  /// This documents nothing
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("DanglingDocComment.04") {
    val input =
      """
        |trait Test[t] {}
        |instance Test[Int32] {
        |  /// This documents nothing
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("DanglingDocComment.05") {
    val input =
      """
        |eff MyEff {
        |  /// This documents nothing
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("MangledModule.02") {
    val input =
      """
        |mod Bar {
        |    legumes provide healthy access to proteins
        |    pub def foo(): Int32 = 123
        |    /// This is not quite finished
        |    pub def
        |}
        |def main(): Int32 = Bar.foo()
        |
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadTrait.01") {
    val input =
      """
        |trait DoesNothing[a
        |  pub def noop(x: a): Unit = ???
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadEnum.01") {
    val input =
      """
        |enum Legumes { Chickpea, Beans }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadEnum.02") {
    val input =
      """
        |enum Legumes[a { case Chickpea(a), case Beans }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadEnum.03") {
    val input =
      """
        |enum USD[a](a
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadEnum.04") {
    val input =
      """
        |// A Suit type deriving an Eq and ToString instance
        |enum Suit with Eq, ToString {
        |    case
        |    //case Clubs
        |    //case Hearts
        |    //case Spades
        |    //case Diamonds
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("TypeAlias.01") {
    val input =
      """
        |type alias M[k, = Map[k, Result[String, k]]
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("TypeAlias.02") {
    val input =
      """
        |type alias M[k] = Map[k, Result[String, ]
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadCallExpr.01") {
    val input =
      """
        |def foo(x: Int32, y: Int32): Int32 = bar(1,
        |// Note: We need an enum here. If we just had main, it would get consumed as a LetRecDef.
        |enum Legumes { case ChickPea, Beans }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadCall.02") {
    val input =
      """
        |def foo(x: Int32, y: Int32): Int32 = {
        |  bar( 1 + ;
        |  let x =
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadQualifiedPath.01") {
    val input =
      """
        |def foo(): In32 = Bar.
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadRecordSelect.01") {
    val input =
      """
        |def foo(): In32 = bar().
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadBinaryOperation.01") {
    val input =
      """
        |def foo(): Int32 = { 1 + }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadBinaryOperation.02") {
    val input =
      """
        |def foo(): Int32 = # 2
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadWithout.01") {
    val input =
      """
        |def foo(): Int32 = 1 without { IO
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadWithout.02") {
    val input =
      """
        |def foo(): Int32 = 1 without
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadLambda.01") {
    val input =
      """
        |def foo(): Int32 = { () -> ; 1 }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadLambda.02") {
    val input =
      """
        |def foo(): Int32 = { (a, ) -> ; 1 }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadTuple.01") {
    val input =
      """
        |def foo(): (Int32, Int32) = (1, )
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
  }

  test("BadUnary.01") {
    val input =
      """
        |def foo(): Int32 = { lazy; 1 }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadForFragments.01") {
    val input =
      """
        |def foo(): Int32 =
        |    forA ( x <- bar(); y <- baz() yield ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadForFragments.02") {
    val input =
      """
        |def foo(): Int32 =
        |    forA ( x <- bar(); y <- baz(); yield ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadForFragments.03") {
    val input =
      """
        |def foo(): Int32 =
        |    forA ( x <- bar(), y <- baz() yield ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadIfThenElse.01") {
    val input =
      """
        |def foo(): Int32 = if 123 else 321
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadIfThenElse.02") {
    val input =
      """
        |def foo(): Int32 = if () 123 else 321
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadIfThenElse.03") {
    val input =
      """
        |def foo(): Int32 = if (false) 123
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadLetMatch.01") {
    val input =
      """
        |def foo(): Int32 = {
        | let (_x, ) = (1, 2);
        | 123
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadLetMatch.02") {
    val input =
      """
        |def foo(): Int32 = {
        | let (, x  = (1, 2);
        | x
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadLetMatch.03") {
    val input =
      """
        |def foo(): Int32 = {
        | let x:  = enum;
        | x
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadMatch.01") {
    val input =
      """
        |def foo(): Int32 = match () { case }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadMatch.02") {
    val input =
      """
        |def map(t: Int32): Int32 = match t
        |def main(): Int32 = 123
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadFixpointConstraint.01") {
    val input =
      """
        |def getFacts(): #{ ParentOf(String, String), AncestorOf(String, String) } = #{
        |    ParentOf("Pompey", "Strabo").,
        |    ParentOf("Sextus", "Pompey").
        |}
        |def main(): Int32 = 123
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadType.01") {
    val input =
      """
        |def foo(): List[Int32 = ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadType.02") {
    val input =
      """
        |def foo(): Int32 -> = ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadType.03") {
    val input =
      """
        |def foo(): Int32 -> Int32 \ { = ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadType.04") {
    val input =
      """
        |def foo(): #{ Node() | = ???
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadInstance.01") {
    val input =
      """
        |instance Order { }
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadEff.01") {
    val input =
      """
        |pub eff Print {
        |    /
        |    pub def printIt(): Unit
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadConstraintSet.01") {
    val input =
      """
        |def baz(): #{ Path(Int32, Int32) } = #{
        |    Edge(1, 2).
        |    Path(x, y) :-
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("ChainedApplyRecordSelect.01") {
    val input =
      """
        |def main(): Unit = ()
        |
        |def foo(): Int32 =
        |    let f = () -> { g = () -> { h = () -> 12 } };
        |    f()#
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("ChainedApplyRecordSelect.02") {
    val input =
      """
        |def main(): Unit = ()
        |
        |def foo(): Int32 =
        |    let f = () -> { g = () -> { h = () -> 12 } };
        |    f()#g()#
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("LetMatchNoStatement.01") {
    val input =
      """
        |def foo(): Unit \ IO  =
        |    let x =
        |    println("Hello World!")
        |
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("LetRecDefNoStatement.01") {
    val input =
      """
        |def foo(): Unit \ IO =
        |    def bar(): Int32 = 123
        |def main(): Int32 = 456
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("MissingWithBody.01") {
    val input =
      """
        |def foo(): Bool =
        |    let result = run {
        |        mutual1(10)
        |    } with handler AskTell ;
        |    Assert.eq(Some(84), result)
        |def main(): Int32 = 123
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("MissingCatchBody.01") {
    val input =
      """
        |def foo(): Bool =
        |    try { true } catch
        |def main(): Int32 = 123
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("MissingRunBody.01") {
    val input =
      """
        |def foo(): Bool =
        |    run { true }
        |def main(): Int32 = 123
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("MissingTryBody.01") {
    val input =
      """
        |def foo(): Bool =
        |    try { true }
        |def main(): Int32 = 123
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("Regression.#7646") {
    val input =
      """
        |instance ToString[Card] {
        |    pub def toString(x: Card): String = match x {
        |        case Card.Card(r, s) _ => "${r} of ${s}"
        |    }
        |}
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectErrorOnCheck[ParseError](result)
    expectMain(result)
  }

  test("BadArrowEffectApplication.01") {
    val input =
      """
        |type alias T[_a] = Unit
        |pub def seqCheck(f: a -> a \ l: T[a]): a = ???
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }
}

/**
  * Note that CompilerSuite and LibrarySuite covers the positive testing of the parser well.
  */
class TestParserHappy extends AnyFunSuite with TestUtils {
  test("DetectRecord.01") {
    val input =
      """
        |pub def foo(): { x = Int32 } = {
        |    // This is a comment
        |    x = 1000
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("ExtensibleType.01") {
    val input =
      """
        |pub def foo(): #| A(Int32) |# = ???
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectSuccess(result)
  }
}

/**
  * Tests errors without testing recovery.
  */
class TestParserSad extends AnyFunSuite with TestUtils {
  test("ParseError.Interpolation.01") {
    val input = s"""pub def foo(): String = "$${1 + }""""
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("ParseError.Interpolation.02") {
    val input = s"""pub def foo(): String = "$${1 {}""""
    val result = compile(input, Options.TestWithLibNix)
    expectError[LexerError](result)
  }

  test("ParseError.Interpolation.03") {
    val input = """pub def foo(): String = "\\${""""
    val result = compile(input, Options.TestWithLibNix)
    expectError[LexerError](result)
  }

  test("ParseError.EnumCase.01") {
    val input =
      """
        |enum E {
        |    case C()
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("ParseError.ParYield.01") {
    val input =
      """
        |def f(): Int32 = par () yield 1
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("ParseError.ParYield.02") {
    val input =
      """
        |def f(): Int32 = par a <- 1 yield a
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("ParseError.ParYield.03") {
    val input =
      """
        |def f(): (Int32, Int32) = par (a <- let b = 1; b; c <- 2) yield (a, c)
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("ParseError.InstanceOf.01") {
    val input =
      """
        |def foo(): Bool =
        |    1000ii instanceof java.math.BigInteger
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalEffectTypeParams.01") {
    val input =
      """
        |eff MyEffect[a]
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[WeederError.IllegalEffectTypeParams](result)
  }

  test("IllegalEffectTypeParams.02") {
    val input =
      """
        |eff MyEffect {
        |    def op[a](x: a): Unit
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[WeederError.IllegalEffectTypeParams](result)
  }

  test("IllegalEffectTypeParams.03") {
    val input =
      """
        |eff MyEffect[a] {
        |    def op[b](x: a, y: b): Unit
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[WeederError.IllegalEffectTypeParams](result)
  }

  test("IllegalExtMatchRule.01") {
    val input =
      """
        |def f(): Int32 = ematch xvar A(1) { }
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalExtPattern.01") {
    val input =
      """
        |def f(): Int32 = ematch xvar A(1) {
        |    case 1 => 1
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalExtPattern.02") {
    val input =
      """
        |def f(): Int32 = ematch xvar A(1) {
        |    case A.B(2) => 1
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalExtPattern.03") {
    val input =
      """
        |def f(): Int32 = ematch xvar A(1) {
        |    case A((1, 2)) => 1
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalExtPattern.04") {
    val input =
      """
        |def f(): Int32 = ematch xvar A(1) {
        |    case A(B(1)) => 1
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalExtPattern.05") {
    val input =
      """
        |def f(): Int32 = ematch xvar A(1) {
        |    case A(1) => 1
        |    case A(1) => 1
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalExtPattern.06") {
    val input =
      """
        |def f(): Int32 = ematch xvar A(1) {
        |    case A(1) => 1
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalExtPattern.07") {
    val input =
      """
        |def f(): Int32 = ematch xvar A(1) {
        |    case A(1) => 1
        |    case A(1) => 1
        |    case A(1) => 1
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalExtTag.01") {
    val input =
      """
        |def f(): Int32 = ematch xvar A() -> 123 {
        |    case A(x) => x
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  ignore("IllegalExtTag.02") {
    val input =
      """
        |def f(): Int32 = ematch xvar A (1) {
        |    case A(x) => x
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalOperationWithOutReturnType.01") {
    val input =
      """
        |eff E{
        |    def op()
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalOperationWithOutReturnType.02") {
    val input =
      """
        |eff E{
        |    def op():
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalEffectfulOperation.01") {
    val input =
      """
        |eff E {
        |    def op(): Unit \ IO
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibMin)
    expectError[WeederError.IllegalEffectfulOperation](result)
  }

  test("IllegalEffectfulOperation.02") {
    val input =
      """
        |eff E {
        |    def op(): Unit \ E
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[WeederError.IllegalEffectfulOperation](result)
  }

  test("IllegalEffectfulOperation.03") {
    val input =
      """
        |eff E {
        |    def op(): Unit \ ef
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[WeederError.IllegalEffectfulOperation](result)
  }

  test("IllegalEffectfulOperation.04") {
    val input =
      """
        |eff A {
        |    pub def op(): Unit
        |}
        |eff E {
        |    def op(): Unit \ A
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[WeederError.IllegalEffectfulOperation](result)
  }

  test("IllegalEnum.01") {
    val input =
      """
        |enum E(Int32) { }
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[WeederError.IllegalEnum](result)
  }

  test("IllegalEnum.02") {
    val input =
      """
        |enum E(a) { }
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[WeederError.IllegalEnum](result)
  }

  test("IllegalEnum.03") {
    val input =
      """
        |enum Foo()
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalModule.01") {
    val input =
      """
        |mod DelayMap {
        |    @Experimental
        |    pub de
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalModuleName.01") {
    val input =
      """
        |mod mymod {
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalModuleName.02") {
    val input =
      """
        |mod Mymod.othermod {
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("IllegalModuleName.03") {
    val input =
      """
        |mod Mymod {
        |    mod othermod {
        |    }
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("BadThrow.01") {
    val input =
      """
        |def foo(): Unit \ IO = throw
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("StructNoTParams.01") {
    val input =
      """
        |struct S { }
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("NewStructNoBody.01") {
    val input =
      """
        |def foo(s: S[r]): Int32 = new Struct @ r |> ???
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("Nested.Mod.Eff") {
    val input =
      """
        |eff E {
        |    mod
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("Nested.Mod.Instance") {
    val input =
      """
        |instance E {
        |    mod
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("Nested.Mod.Trait") {
    val input =
      """
        |trait E {
        |    mod
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("BadTupleEnd.01") {
    val input =
      """
        |def foo(x: Int32): (Int32, Int32) = (x, |)
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("MinusCase.NoBrace.01") {
    val input =
      """
        |def foo(): Int32 = match 0 {
        |  case -
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("MinusCase.YesBrace.01") {
    val input =
      """
        |def foo(): Int32 = match 0 {
        |  case -
        |}
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

  test("MinusCase.Capital.01") {
    val input =
      """
        |def foo(): Int32 = match 0 {
        |  case -ABC
        |""".stripMargin
    val result = compile(input, Options.TestWithLibNix)
    expectError[ParseError](result)
  }

}
