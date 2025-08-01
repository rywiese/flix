/*
 * Copyright 2020 Magnus Madsen
 * Copyright 2024 Alexander Dybdahl Troelsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.lsp.*
import ca.uwaterloo.flix.api.lsp.acceptors.InsideAcceptor
import ca.uwaterloo.flix.api.lsp.consumers.StackConsumer
import ca.uwaterloo.flix.language.ast.TypedAst.Root
import ca.uwaterloo.flix.language.ast.shared.{EqualityConstraint, SymUse, TraitConstraint}
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL.*

object GotoProvider {

  /**
    * Processes a goto request.
    */
  def processGoto(uri: String, pos: Position)(implicit root: Root): Option[LocationLink] = {
    val gotoRight = searchRight(uri, pos).flatMap(goto)
    val gotoLeft = searchLeft(uri, pos).flatMap(goto)

    gotoRight
      .orElse(gotoLeft)
      .filter(_.targetUri.startsWith("file://")) // We do not support goto for non-file URIs, which is the case for the standard library.
  }

  /**
    * Returns the most specific AST node under the space immediately right of the thin cursor if there is one.
    * Otherwise, returns [[None]].
    *
    * Note that the given [[Position]] `pos` of the cursor is interpreted as the [[Position]]
    * to the immediate right of the thin cursor.
    *
    * @param uri  the URI of the file that the cursor is in.
    * @param pos  the [[Position]] to the immediate right of the thin cursor.
    * @param root the root AST node of the Flix program.
    * @return the most specific AST node under the space immediately right of the thin cursor
    *         if there is one. Otherwise, returns [[None]].
    */
  private def searchRight(uri: String, pos: Position)(implicit root: Root): Option[AnyRef] = search(uri, pos)

  /**
    * Returns the most specific AST node under the space immediately left of the thin cursor.
    *
    * Note that the given [[Position]] `pos` of the cursor is interpreted as the [[Position]]
    * to the immediate right of the thin cursor.
    *
    * @param uri  URI of the file that the cursor is in.
    * @param pos  [[Position]] to the immediate right of the thin cursor.
    * @param root Root AST node of the Flix Program.
    * @return the most specific AST node under the space immediately left of the thin cursor
    *         if there is one. Otherwise, returns [[None]].
    */
  private def searchLeft(uri: String, pos: Position)(implicit root: Root): Option[AnyRef] = {
    if (pos.character >= 2) {
      val left = Position(pos.line, pos.character - 1)
      search(uri, left)
    } else {
      None
    }
  }

  /**
    * Returns the most specific AST node under the [[Position]] `pos` if there is one.
    * Otherwise, returns [[None]].
    *
    * @param uri  URI of the file that the [[Position]] `pos` is in.
    * @param pos  [[Position]] that we're searching under.
    * @param root Root AST node of the Flix program.
    * @return The most specific AST node under the [[Position]] `pos`
    *         if there is one. Otherwise, returns [[None]].
    */
  private def search(uri: String, pos: Position)(implicit root: Root): Option[AnyRef] = {
    val consumer = StackConsumer();
    Visitor.visitRoot(root, consumer, InsideAcceptor(uri, pos))
    consumer.getStack.filter(isReal).headOption
  }

  /**
    * Returns an LSP Goto response for when the cursor is on `x`, if `x` is the occurrence of a [[Symbol]].
    * Otherwise, returns [[None]].
    *
    * The LSP Goto response will take the form of an LSP [[LocationLink]] from the [[SourceLocation]] of
    * `x` to the [[SourceLocation]] of the [[Symbol]]s definition site.
    *
    * @param x    Object that the cursor is on.
    * @param root Root AST node of the Flix program
    * @return LSP Goto response for when the cursor is on `x` if `x` is an occurrence of a [[Symbol]].
    *         Otherwise, returns [[None]].
    */
  private def goto(x: AnyRef)(implicit root: Root): Option[LocationLink] = x match {
    // Assoc Types
    case SymUse.AssocTypeSymUse(sym, loc) => Some(LocationLink.fromAssocTypeSym(sym, loc))
    // Defs
    case SymUse.DefSymUse(sym, loc) => Some(LocationLink.fromDefSym(sym, loc))
    // Effects
    case SymUse.EffSymUse(sym, qname) => Some(LocationLink.fromEffSym(sym, qname.loc))
    case Type.Cst(TypeConstructor.Effect(sym, _), loc) => Some(LocationLink.fromEffSym(sym, loc))
    case SymUse.OpSymUse(sym, loc) => Some(LocationLink.fromOpSym(sym, loc))
    // Enums
    case Type.Cst(TypeConstructor.Enum(sym, _), loc) => Some(LocationLink.fromEnumSym(sym, loc))
    case SymUse.CaseSymUse(sym, loc) => Some(LocationLink.fromCaseSym(sym, loc))
    // Struct
    case Type.Cst(TypeConstructor.Struct(sym, _), loc) => Some(LocationLink.fromStructSym(sym, loc))
    case SymUse.StructFieldSymUse(sym, loc) => Some(LocationLink.fromStructFieldSym(sym, loc))
    // Traits
    case SymUse.TraitSymUse(sym, loc) => Some(LocationLink.fromTraitSym(sym, loc))
    case SymUse.SigSymUse(sym, loc) => Some(LocationLink.fromSigSym(sym, loc))
    // Type Vars
    case Type.Var(sym, loc) => Some(LocationLink.fromTypeVarSym(sym, loc))
    // Vars
    case TypedAst.Expr.Var(sym, _, loc) => Some(LocationLink.fromVarSym(sym, loc))
    case _ => None
  }

  private def isReal(x: AnyRef): Boolean = x match {
    case TypedAst.Trait(_, _, _, _, _, _, _, _, _, loc) => loc.isReal
    case TypedAst.Instance(_, _, _, _, _, _, _, _, _, _, loc) => loc.isReal
    case TypedAst.Sig(_, _, _, loc) => loc.isReal
    case TypedAst.Def(_, _, _, loc) => loc.isReal
    case TypedAst.Enum(_, _, _, _, _, _, _, loc) => loc.isReal
    case TypedAst.Struct(_, _, _, _, _, _, _, loc) => loc.isReal
    case TypedAst.RestrictableEnum(_, _, _, _, _, _, _, _, loc) => loc.isReal
    case TypedAst.TypeAlias(_, _, _, _, _, _, loc) => loc.isReal
    case TypedAst.AssocTypeSig(_, _, _, _, _, _, loc) => loc.isReal
    case TypedAst.AssocTypeDef(_, _, _, _, _, loc) => loc.isReal
    case TypedAst.Effect(_, _, _, _, _, _, loc) => loc.isReal
    case TypedAst.Op(_, _, loc) => loc.isReal
    case exp: TypedAst.Expr => exp.loc.isReal
    case pat: TypedAst.Pattern => pat.loc.isReal
    case TypedAst.RestrictableChoosePattern.Wild(_, loc) => loc.isReal
    case TypedAst.RestrictableChoosePattern.Var(_, _, loc) => loc.isReal
    case TypedAst.RestrictableChoosePattern.Tag(_, _, _, loc) => loc.isReal
    case TypedAst.RestrictableChoosePattern.Error(_, loc) => loc.isReal
    case p: TypedAst.Predicate => p.loc.isReal
    case TypedAst.Binder(sym, _) => sym.loc.isReal
    case TypedAst.Case(_, _, _, loc) => loc.isReal
    case TypedAst.StructField(_, _, loc) => loc.isReal
    case TypedAst.RestrictableCase(_, _, _, loc) => loc.isReal
    case TypedAst.Constraint(_, _, _, loc) => loc.isReal
    case TypedAst.ConstraintParam(_, _, loc) => loc.isReal
    case TypedAst.FormalParam(_, _, _, _, loc) => loc.isReal
    case TypedAst.PredicateParam(_, _, loc) => loc.isReal
    case TypedAst.JvmMethod(_, _, _, _, _, loc) => loc.isReal
    case TypedAst.CatchRule(_, _, _, _) => true
    case TypedAst.HandlerRule(_, _, _, _) => true
    case TypedAst.TypeMatchRule(_, _, _, _) => true
    case TypedAst.SelectChannelRule(_, _, _, _) => true
    case TypedAst.TypeParam(_, _, loc) => loc.isReal
    case TypedAst.ParYieldFragment(_, _, loc) => loc.isReal

    case SymUse.AssocTypeSymUse(_, loc) => loc.isReal
    case SymUse.CaseSymUse(_, loc) => loc.isReal
    case SymUse.DefSymUse(_, loc) => loc.isReal
    case SymUse.EffSymUse(_, qname) => qname.loc.isReal
    case SymUse.LocalDefSymUse(_, loc) => loc.isReal
    case SymUse.OpSymUse(_, loc) => loc.isReal
    case SymUse.RestrictableCaseSymUse(_, loc) => loc.isReal
    case SymUse.RestrictableEnumSymUse(_, loc) => loc.isReal
    case SymUse.SigSymUse(_, loc) => loc.isReal
    case SymUse.StructFieldSymUse(_, loc) => loc.isReal
    case SymUse.TraitSymUse(_, loc) => loc.isReal

    case TraitConstraint(_, _, loc) => loc.isReal

    case EqualityConstraint(_, _, _, loc) => loc.isReal

    case _: Symbol => true
    case tpe: Type => tpe.loc.isReal
    case _ => false
  }
}
