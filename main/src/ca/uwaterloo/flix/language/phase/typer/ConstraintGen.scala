/*
 * Copyright 2015-2023 Magnus Madsen, Matthew Lutze
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
package ca.uwaterloo.flix.language.phase.typer

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.KindedAst.{Expr, ExtPattern}
import ca.uwaterloo.flix.language.ast.shared.SymUse.{DefSymUse, LocalDefSymUse, OpSymUse, SigSymUse}
import ca.uwaterloo.flix.language.ast.shared.{CheckedCastType, Scope, VarText}
import ca.uwaterloo.flix.language.ast.{Kind, KindedAst, Name, Scheme, SemanticOp, SourceLocation, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.phase.unification.Substitution
import ca.uwaterloo.flix.util.{InternalCompilerException, Subeffecting}

/**
  * This phase generates a list of type constraints, which include
  *   - equality constraints `tpe1 ~ tpe2`
  *   - trait constraints `C[tpe1]`
  *   - purification constraints `eff1 ~ eff2[sym ↦ Pure]`
  *
  * We gather constraints as we traverse each def.
  * Constraints are later resolved in ConstraintResolution.
  */
object ConstraintGen {

  /**
    * Generates constraints for the given expression `exp0`, adding them to the type context `c`.
    *
    * Returns the type of the expression and its effect.
    * The type and effect may include variables that must be resolved.
    */
  def visitExp(exp0: KindedAst.Expr)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): (Type, Type) = {
    implicit val scope: Scope = c.getScope
    exp0 match {
      case Expr.Var(sym, _) =>
        val resTpe = sym.tvar
        val resEff = Type.Pure
        (resTpe, resEff)

      case Expr.Hole(_, _, tpe, eff, _) =>
        val resTpe = tpe
        val resEff = eff
        (resTpe, resEff)

      case Expr.HoleWithExp(exp, _, tvar, evar, loc) =>
        // We ignore exp's type and allow the hole to have any type.
        // We allow the effect to be any superset of exp's effect.
        val (_, eff) = visitExp(exp)
        val atLeastEff = Type.mkUnion(eff, freshVar(Kind.Eff, loc), loc.asSynthetic)
        c.unifyType(atLeastEff, evar, loc)
        val resTpe = tvar
        val resEff = atLeastEff
        (resTpe, resEff)

      case e: Expr.OpenAs => RestrictableChooseConstraintGen.visitOpenAs(e)

      case Expr.Use(_, _, exp, _) =>
        visitExp(exp)

      case Expr.Cst(cst, _) =>
        val resTpe = Type.constantType(cst)
        val resEff = Type.Pure
        (resTpe, resEff)

      case Expr.ApplyClo(exp1, exp2, tvar, evar, loc) =>
        val lambdaBodyType = freshVar(Kind.Star, loc)
        val lambdaBodyEff = freshVar(Kind.Eff, loc)
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        c.expectType(Type.mkArrowWithEffect(tpe2, lambdaBodyEff, lambdaBodyType, loc), tpe1, loc)
        c.unifyType(tvar, lambdaBodyType, loc)
        c.unifyType(evar, Type.mkUnion(lambdaBodyEff :: eff1 :: eff2 :: Nil, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.ApplyDef(DefSymUse(sym, loc1), exps, targs, itvar, tvar, evar, loc2) =>
        val defn = root.defs(sym)

        // Pseudo variable for source to flow into
        val pvar = Type.freshVar(Kind.Eff, loc1)

        val tparams = defn.spec.tparams.map(_.sym)
        val subst = Substitution(tparams.zip(targs).toMap)

        val tconstrs = defn.spec.tconstrs.map(subst.apply).map(_.copy(loc = loc2))
        val econstrs = defn.spec.econstrs.map(subst.apply).map(_.copy(loc = loc2))

        val declaredEff = subst(defn.spec.eff)
        val declaredResultType = subst(defn.spec.tpe)
        val declaredArgumentTypes = defn.spec.fparams.map(_.tpe).map(subst.apply)
        val declaredType = Type.mkUncurriedArrowWithEffect(declaredArgumentTypes, declaredEff, declaredResultType, loc2)

        val (tpes, effs) = exps.map(visitExp).unzip

        c.unifyType(itvar, declaredType, loc2)
        c.expectTypeArguments(sym, declaredArgumentTypes, tpes, exps.map(_.loc))
        c.addClassConstraints(tconstrs, loc2)
        c.addEqualityConstraints(econstrs, loc2)
        c.unifyType(tvar, declaredResultType, loc2)
        c.unifySource(pvar, declaredEff, loc2)
        c.unifyType(evar, Type.mkUnion(pvar :: effs, loc2), loc2)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.ApplyLocalDef(LocalDefSymUse(sym, loc1), exps, arrowTvar, tvar, evar, loc2) =>
        val (tpes, effs) = exps.map(visitExp).unzip
        val defEff = freshVar(Kind.Eff, loc1)
        val actualDefTpe = Type.mkUncurriedArrowWithEffect(tpes, defEff, tvar, loc1)
        c.unifyType(actualDefTpe, arrowTvar, loc1)
        c.expectType(sym.tvar, actualDefTpe, loc1)
        c.unifyType(evar, Type.mkUnion(defEff :: effs, loc2), loc2)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.ApplyOp(OpSymUse(sym, loc1), exps, tvar, evar, loc2) =>
        val op = lookupOp(sym, loc1)
        val (tconstrs0, econstrs0, declaredType, _) = Scheme.instantiate(op.spec.sc, loc1.asSynthetic)
        val tconstrs = tconstrs0.map(_.copy(loc = loc2))
        val econstrs = econstrs0.map(_.copy(loc = loc2))
        val declaredEff = declaredType.arrowEffectType
        val declaredArgumentTypes = declaredType.arrowArgTypes
        val declaredResultType = generalizeVoid(declaredType.arrowResultType)
        val (tpes, effs) = exps.map(visitExp).unzip
        c.expectTypeArguments(sym, declaredArgumentTypes, tpes, exps.map(_.loc))
        c.addClassConstraints(tconstrs, loc2)
        c.addEqualityConstraints(econstrs, loc2)
        c.unifyType(tvar, declaredResultType, loc2)
        c.unifyType(evar, Type.mkUnion(declaredEff :: effs, loc2), loc2)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.ApplySig(SigSymUse(sym, loc1), exps, targ, targs, itvar, tvar, evar, loc2) =>
        val trt = root.traits(sym.trt)
        val sig = trt.sigs(sym)

        val mapping = (trt.tparam.sym -> targ) :: sig.spec.tparams.map(_.sym).zip(targs)
        val subst = Substitution(mapping.toMap)
        val tconstrs = sig.spec.tconstrs.map(subst.apply).map(_.copy(loc = loc2))
        val econstrs = sig.spec.econstrs.map(subst.apply).map(_.copy(loc = loc2))

        val declaredEff = subst(sig.spec.eff)
        val declaredResultType = subst(sig.spec.tpe)
        val declaredArgumentTypes = sig.spec.fparams.map(_.tpe).map(subst.apply)
        val declaredType = Type.mkUncurriedArrowWithEffect(declaredArgumentTypes, declaredEff, declaredResultType, loc1)

        val (tpes, effs) = exps.map(visitExp).unzip
        c.expectTypeArguments(sym, declaredArgumentTypes, tpes, exps.map(_.loc))
        c.addClassConstraints(tconstrs, loc2)
        c.addEqualityConstraints(econstrs, loc2)
        c.unifyType(itvar, declaredType, loc2)
        c.unifyType(tvar, declaredResultType, loc2)
        c.unifyType(evar, Type.mkUnion(declaredEff :: effs, loc2), loc2)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.Lambda(fparam, exp, allowSubeffecting, loc) =>
        c.unifyType(fparam.sym.tvar, fparam.tpe, loc)
        val (tpe, eff0) = visitExp(exp)
        // SUB-EFFECTING: Check if sub-effecting is enabled for lambda expressions.
        val shouldSubeffect = {
          val enabled = flix.options.xsubeffecting.contains(Subeffecting.Lambdas)
          val useless = exp match {
            case Expr.Ascribe(_, _, Some(Type.Pure), _, _) => true
            case _ => false
          }
          enabled && allowSubeffecting && !useless
        }
        val eff = if (shouldSubeffect) Type.mkUnion(eff0, Type.freshEffSlackVar(loc), loc) else eff0
        val resTpe = Type.mkArrowWithEffect(fparam.tpe, eff, tpe, loc)
        val resEff = Type.Pure
        (resTpe, resEff)

      case KindedAst.Expr.Unary(sop, exp, tvar, _) => sop match {
        case SemanticOp.BoolOp.Not =>
          val (tpe, eff) = visitExp(exp)
          c.expectType(expected = Type.Bool, actual = tpe, exp.loc)
          c.unifyType(Type.Bool, tvar, exp.loc)
          val resTpe = tvar
          val resEff = eff
          (resTpe, resEff)

        case SemanticOp.Float32Op.Neg =>
          val (tpe, eff) = visitExp(exp)
          c.expectType(expected = Type.Float32, actual = tpe, exp.loc)
          c.unifyType(Type.Float32, tvar, exp.loc)
          val resTpe = tvar
          val resEff = eff
          (resTpe, resEff)

        case SemanticOp.Float64Op.Neg =>
          val (tpe, eff) = visitExp(exp)
          c.expectType(expected = Type.Float64, actual = tpe, exp.loc)
          c.unifyType(Type.Float64, tvar, exp.loc)
          val resTpe = tvar
          val resEff = eff
          (resTpe, resEff)

        case SemanticOp.Int8Op.Neg | SemanticOp.Int8Op.Not =>
          val (tpe, eff) = visitExp(exp)
          c.expectType(expected = Type.Int8, actual = tpe, exp.loc)
          c.unifyType(Type.Int8, tvar, exp.loc)
          val resTpe = tvar
          val resEff = eff
          (resTpe, resEff)

        case SemanticOp.Int16Op.Neg | SemanticOp.Int16Op.Not =>
          val (tpe, eff) = visitExp(exp)
          c.expectType(expected = Type.Int16, actual = tpe, exp.loc)
          c.unifyType(Type.Int16, tvar, exp.loc)
          val resTpe = tvar
          val resEff = eff
          (resTpe, resEff)

        case SemanticOp.Int32Op.Neg | SemanticOp.Int32Op.Not =>
          val (tpe, eff) = visitExp(exp)
          c.expectType(expected = Type.Int32, actual = tpe, exp.loc)
          c.unifyType(Type.Int32, tvar, exp.loc)
          val resTpe = tvar
          val resEff = eff
          (resTpe, resEff)

        case SemanticOp.Int64Op.Neg | SemanticOp.Int64Op.Not =>
          val (tpe, eff) = visitExp(exp)
          c.expectType(expected = Type.Int64, actual = tpe, exp.loc)
          c.unifyType(Type.Int64, tvar, exp.loc)
          val resTpe = tvar
          val resEff = eff
          (resTpe, resEff)
      }

      case KindedAst.Expr.Binary(sop, exp1, exp2, tvar, loc) => sop match {

        case SemanticOp.BoolOp.And | SemanticOp.BoolOp.Or =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.expectType(expected = Type.Bool, actual = tpe1, exp1.loc)
          c.expectType(expected = Type.Bool, actual = tpe2, exp2.loc)
          c.unifyType(tvar, Type.Bool, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.Float32Op.Add | SemanticOp.Float32Op.Sub | SemanticOp.Float32Op.Mul | SemanticOp.Float32Op.Div
             | SemanticOp.Float32Op.Exp =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.expectType(expected = Type.Float32, actual = tpe1, exp1.loc)
          c.expectType(expected = Type.Float32, actual = tpe2, exp2.loc)
          c.unifyType(tvar, Type.Float32, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.Float64Op.Add | SemanticOp.Float64Op.Sub | SemanticOp.Float64Op.Mul | SemanticOp.Float64Op.Div
             | SemanticOp.Float64Op.Exp =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.expectType(expected = Type.Float64, actual = tpe1, exp1.loc)
          c.expectType(expected = Type.Float64, actual = tpe2, exp2.loc)
          c.unifyType(tvar, Type.Float64, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.Int8Op.Add | SemanticOp.Int8Op.Sub | SemanticOp.Int8Op.Mul | SemanticOp.Int8Op.Div
             | SemanticOp.Int8Op.Rem | SemanticOp.Int8Op.Exp
             | SemanticOp.Int8Op.And | SemanticOp.Int8Op.Or | SemanticOp.Int8Op.Xor =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.expectType(expected = Type.Int8, actual = tpe1, exp1.loc)
          c.expectType(expected = Type.Int8, actual = tpe2, exp2.loc)
          c.unifyType(tvar, Type.Int8, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.Int16Op.Add | SemanticOp.Int16Op.Sub | SemanticOp.Int16Op.Mul | SemanticOp.Int16Op.Div
             | SemanticOp.Int16Op.Rem | SemanticOp.Int16Op.Exp
             | SemanticOp.Int16Op.And | SemanticOp.Int16Op.Or | SemanticOp.Int16Op.Xor =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.expectType(expected = Type.Int16, actual = tpe1, exp1.loc)
          c.expectType(expected = Type.Int16, actual = tpe2, exp2.loc)
          c.unifyType(tvar, Type.Int16, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.Int32Op.Add | SemanticOp.Int32Op.Sub | SemanticOp.Int32Op.Mul | SemanticOp.Int32Op.Div
             | SemanticOp.Int32Op.Rem | SemanticOp.Int32Op.Exp
             | SemanticOp.Int32Op.And | SemanticOp.Int32Op.Or | SemanticOp.Int32Op.Xor =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.expectType(expected = Type.Int32, actual = tpe1, exp1.loc)
          c.expectType(expected = Type.Int32, actual = tpe2, exp2.loc)
          c.unifyType(tvar, Type.Int32, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.Int64Op.Add | SemanticOp.Int64Op.Sub | SemanticOp.Int64Op.Mul | SemanticOp.Int64Op.Div
             | SemanticOp.Int64Op.Rem | SemanticOp.Int64Op.Exp
             | SemanticOp.Int64Op.And | SemanticOp.Int64Op.Or | SemanticOp.Int64Op.Xor =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.expectType(expected = Type.Int64, actual = tpe1, exp1.loc)
          c.expectType(expected = Type.Int64, actual = tpe2, exp2.loc)
          c.unifyType(tvar, Type.Int64, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.Int8Op.Shl | SemanticOp.Int8Op.Shr
             | SemanticOp.Int16Op.Shl | SemanticOp.Int16Op.Shr
             | SemanticOp.Int32Op.Shl | SemanticOp.Int32Op.Shr
             | SemanticOp.Int64Op.Shl | SemanticOp.Int64Op.Shr =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.unifyType(tvar, tpe1, loc)
          c.expectType(expected = Type.Int32, actual = tpe2, exp2.loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.BoolOp.Eq | SemanticOp.BoolOp.Neq
             | SemanticOp.CharOp.Eq | SemanticOp.CharOp.Neq
             | SemanticOp.Float32Op.Eq | SemanticOp.Float32Op.Neq
             | SemanticOp.Float64Op.Eq | SemanticOp.Float64Op.Neq
             | SemanticOp.Int8Op.Eq | SemanticOp.Int8Op.Neq
             | SemanticOp.Int16Op.Eq | SemanticOp.Int16Op.Neq
             | SemanticOp.Int32Op.Eq | SemanticOp.Int32Op.Neq
             | SemanticOp.Int64Op.Eq | SemanticOp.Int64Op.Neq =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.unifyType(tpe1, tpe2, loc)
          c.unifyType(tvar, Type.Bool, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.CharOp.Lt | SemanticOp.CharOp.Le | SemanticOp.CharOp.Gt | SemanticOp.CharOp.Ge
             | SemanticOp.Float32Op.Lt | SemanticOp.Float32Op.Le | SemanticOp.Float32Op.Gt | SemanticOp.Float32Op.Ge
             | SemanticOp.Float64Op.Lt | SemanticOp.Float64Op.Le | SemanticOp.Float64Op.Gt | SemanticOp.Float64Op.Ge
             | SemanticOp.Int8Op.Lt | SemanticOp.Int8Op.Le | SemanticOp.Int8Op.Gt | SemanticOp.Int8Op.Ge
             | SemanticOp.Int16Op.Lt | SemanticOp.Int16Op.Le | SemanticOp.Int16Op.Gt | SemanticOp.Int16Op.Ge
             | SemanticOp.Int32Op.Lt | SemanticOp.Int32Op.Le | SemanticOp.Int32Op.Gt | SemanticOp.Int32Op.Ge
             | SemanticOp.Int64Op.Lt | SemanticOp.Int64Op.Le | SemanticOp.Int64Op.Gt | SemanticOp.Int64Op.Ge =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.unifyType(tpe1, tpe2, loc)
          c.unifyType(tvar, Type.Bool, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)

        case SemanticOp.StringOp.Concat =>
          val (tpe1, eff1) = visitExp(exp1)
          val (tpe2, eff2) = visitExp(exp2)
          c.expectType(expected = Type.Str, actual = tpe1, exp1.loc)
          c.expectType(expected = Type.Str, actual = tpe2, exp2.loc)
          c.unifyType(tvar, Type.Str, loc)
          val resTpe = tvar
          val resEff = Type.mkUnion(eff1, eff2, loc)
          (resTpe, resEff)
      }

      case Expr.IfThenElse(exp1, exp2, exp3, loc) =>
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        val (tpe3, eff3) = visitExp(exp3)
        c.expectType(expected = Type.Bool, actual = tpe1, exp1.loc)
        c.unifyType(tpe2, tpe3, loc)
        val resTpe = tpe3
        val resEff = Type.mkUnion(eff1, eff2, eff3, loc)
        (resTpe, resEff)

      case Expr.Stm(exp1, exp2, loc) =>
        val (_, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        val resTpe = tpe2
        val resEff = Type.mkUnion(eff1, eff2, loc)
        (resTpe, resEff)

      case Expr.Discard(exp, _) =>
        val (_, eff) = visitExp(exp)
        val resTpe = Type.Unit
        val resEff = eff
        (resTpe, resEff)

      case Expr.Let(sym, exp1, exp2, loc) =>
        val (tpe1, eff1) = visitExp(exp1)
        c.unifyType(sym.tvar, tpe1, exp1.loc)
        val (tpe2, eff2) = visitExp(exp2)
        val resTpe = tpe2
        val resEff = Type.mkUnion(eff1, eff2, loc)
        (resTpe, resEff)

      case Expr.LocalDef(sym, fparams, exp1, exp2, loc) =>
        val (tpe1, eff1) = visitExp(exp1)
        fparams.foreach(fp => c.unifyType(fp.sym.tvar, fp.tpe, loc))
        // SUB-EFFECTING: Check if sub-effecting is enabled for lambda expressions (which include local defs).
        val shouldSubeffect = {
          val enabled = flix.options.xsubeffecting.contains(Subeffecting.Lambdas)
          val useless = exp1 match {
            case Expr.Ascribe(_, _, Some(Type.Pure), _, _) => true
            case _ => false
          }
          enabled && !useless
        }
        val defEff = if (shouldSubeffect) Type.mkUnion(eff1, Type.freshEffSlackVar(loc), loc) else eff1
        val defTpe = Type.mkUncurriedArrowWithEffect(fparams.map(_.tpe), defEff, tpe1, sym.loc)
        c.unifyType(sym.tvar, defTpe, sym.loc)
        val (tpe2, eff2) = visitExp(exp2)
        val resTpe = tpe2
        val resEff = eff2
        (resTpe, resEff)

      case Expr.Region(tpe, _) =>
        val resTpe = tpe
        val resEff = Type.Pure
        (resTpe, resEff)

      case Expr.Scope(sym, regSym, exp, tvar, evar, loc) =>
        // We must visit exp INSIDE the region
        // (i.e. between `enter` and `exit`)
        // because we need to resolve local constraints
        // BEFORE purifying the region as we exit,
        // and we need to be sure that we don't "learn" anything
        // about the outside of the region while inside.
        //
        // We must unify sym.tvar and the region var INSIDE the region
        // because we need to ensure that references to the region are
        // resolved BEFORE purifying the region as we exit.
        c.enterRegion(regSym)
        c.unifyType(sym.tvar, Type.mkRegionToStar(Type.mkRegion(regSym, loc), loc), loc)
        val (tpe, eff) = visitExp(exp)
        c.unifyType(tvar, tpe, loc)
        c.exitRegion(evar, eff, loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.Match(exp, rules, loc) =>
        val (tpe, eff) = visitExp(exp)
        val (patTpes, tpes, effs) = rules.map(visitMatchRule).unzip3
        c.unifyAllTypes(tpe :: patTpes, loc)
        c.unifyAllTypes(tpes, loc)
        val resTpe = tpes.headOption.getOrElse(freshVar(Kind.Star, loc))
        val resEff = Type.mkUnion(eff :: effs, loc)
        (resTpe, resEff)

      case Expr.TypeMatch(exp, rules, loc) =>
        val (_, eff) = visitExp(exp)
        val (tpes, effs) = rules.map(visitTypeMatchRule).unzip
        c.unifyAllTypes(tpes, loc)
        val resTpe = tpes.headOption.getOrElse(freshVar(Kind.Star, loc))
        val resEff = Type.mkUnion(eff :: effs, loc)
        (resTpe, resEff)

      case e: Expr.RestrictableChoose => RestrictableChooseConstraintGen.visitRestrictableChoose(e)

      case Expr.ExtMatch(exp, rules, loc) =>
        val (tpe, eff) = visitExp(exp)
        val (caseTpes, tpes, effs) = rules.map(visitExtMatchRule).unzip3
        val expectedRowType = caseTpes.foldRight(Type.mkSchemaRowEmpty(loc)) {
          case ((name, patTpes), acc) => Type.mkSchemaRowExtend(name, Type.mkRelation(patTpes, loc), acc, loc)
        }
        val expectedSchemaType = Type.mkExtensible(expectedRowType, loc)
        c.unifyType(tpe, expectedSchemaType, exp.loc)
        c.unifyAllTypes(tpes, loc)
        val resTpe = tpes.head // Note: We are guaranteed to have one rule.
        val resEff = Type.mkUnion(eff :: effs, loc)
        (resTpe, resEff)

      case KindedAst.Expr.Tag(symUse, exps, tvar, loc) =>
        val decl = root.enums(symUse.sym.enumSym)
        val caze = decl.cases(symUse.sym)
        // We ignore constraints as tag schemes do not have them
        val (_, _, tagType, _) = Scheme.instantiate(caze.sc, loc.asSynthetic)

        // The tag type is a function from the types of terms to the type of the enum.
        val (tpes, effs) = exps.map(visitExp).unzip
        val constructorBase = Type.mkPureUncurriedArrow(tpes, tvar, loc)
        c.unifyType(tagType, constructorBase, loc)
        val resTpe = tvar
        val resEff = Type.mkUnion(effs, loc)
        (resTpe, resEff)

      case e: Expr.RestrictableTag => RestrictableChooseConstraintGen.visitApplyRestrictableTag(e)

      case KindedAst.Expr.ExtTag(label, exps, tvar, loc) =>
        val pred = Name.Pred(label.name, label.loc)
        val (tpes, effs) = exps.map(visitExp).unzip
        val rest = Type.freshVar(Kind.SchemaRow, loc)
        val tpe = Type.mkRelation(tpes, loc)
        val row = Type.mkSchemaRowExtend(pred, tpe, rest, loc)
        val tagType = Type.mkExtensible(row, loc)
        c.unifyType(tvar, tagType, loc)
        val resTpe = tagType
        val resEff = Type.mkUnion(effs, loc)
        (resTpe, resEff)

      case Expr.Tuple(exps, loc) =>
        val (tpes, effs) = exps.map(visitExp).unzip
        val resTpe = Type.mkTuple(tpes, loc)
        val resEff = Type.mkUnion(effs, loc)
        (resTpe, resEff)

      case Expr.RecordSelect(exp, label, tvar, loc) =>
        //
        // r : { label = tpe | row }
        // -------------------------
        //       r.label : tpe
        //
        val freshRowVar = freshVar(Kind.RecordRow, loc)
        val expectedRowType = Type.mkRecordRowExtend(label, tvar, freshRowVar, loc)
        val expectedRecordType = Type.mkRecord(expectedRowType, loc)
        val (tpe, eff) = visitExp(exp)
        c.unifyType(tpe, expectedRecordType, loc)
        val resTpe = tvar
        val resEff = eff
        (resTpe, resEff)

      case Expr.RecordExtend(label, exp1, exp2, tvar, loc) =>
        //
        //       exp1 : tpe        exp2 : {| r }
        // ---------------------------------------------
        // { label = exp1 | exp2 } : { label  :: tpe | r }
        //
        val freshRowVar = freshVar(Kind.RecordRow, loc)
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        c.unifyType(tpe2, Type.mkRecord(freshRowVar, loc), loc)
        c.unifyType(tvar, Type.mkRecord(Type.mkRecordRowExtend(label, tpe1, freshRowVar, loc), loc), loc)
        val resTpe = tvar
        val resEff = Type.mkUnion(eff1, eff2, loc)
        (resTpe, resEff)

      case Expr.RecordRestrict(label, exp, tvar, loc) =>
        //
        //  exp : { label  :: t | r }
        // -------------------------
        // { -label | exp } : {| r }
        //
        val freshLabelType = freshVar(Kind.Star, loc)
        val freshRowVar = freshVar(Kind.RecordRow, loc)
        val (tpe, eff) = visitExp(exp)
        c.unifyType(tpe, Type.mkRecord(Type.mkRecordRowExtend(label, freshLabelType, freshRowVar, loc), loc), loc)
        c.unifyType(tvar, Type.mkRecord(freshRowVar, loc), loc)
        val resTpe = tvar
        val resEff = eff
        (resTpe, resEff)

      case Expr.ArrayLit(exps, exp, tvar, evar, loc) =>
        val regionVar = freshVar(Kind.Eff, loc)
        val regionType = Type.mkRegionToStar(regionVar, loc)
        val (tpes, effs) = exps.map(visitExp).unzip
        val (tpe, eff) = visitExp(exp)
        c.expectType(expected = regionType, actual = tpe, exp.loc)
        c.unifyAllTypes(tpes, loc)
        val elmTpe = tpes.headOption.getOrElse(freshVar(Kind.Star, loc))
        c.unifyType(tvar, Type.mkArray(elmTpe, regionVar, loc), loc)
        c.unifyType(evar, Type.mkUnion(Type.mkUnion(effs, loc), eff, regionVar, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.ArrayNew(exp1, exp2, exp3, tvar, evar, loc) =>
        val regionVar = freshVar(Kind.Eff, loc)
        val regionType = Type.mkRegionToStar(regionVar, loc)
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        val (tpe3, eff3) = visitExp(exp3)
        c.expectType(expected = regionType, actual = tpe1, loc)
        c.expectType(expected = Type.Int32, actual = tpe3, exp3.loc)
        c.unifyType(tvar, Type.mkArray(tpe2, regionVar, loc), loc)
        c.unifyType(evar, Type.mkUnion(eff1, eff2, eff3, regionVar, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.ArrayLoad(exp1, exp2, tvar, evar, loc) =>
        val regionVar = freshVar(Kind.Eff, loc)
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        c.expectType(expected = Type.mkArray(tvar, regionVar, loc), actual = tpe1, exp1.loc)
        c.expectType(expected = Type.Int32, actual = tpe2, exp2.loc)
        c.unifyType(evar, Type.mkUnion(regionVar, eff1, eff2, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.ArrayStore(exp1, exp2, exp3, evar, loc) =>
        val elmVar = freshVar(Kind.Star, loc)
        val regionVar = freshVar(Kind.Eff, loc)
        val arrayType = Type.mkArray(elmVar, regionVar, loc)
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        val (tpe3, eff3) = visitExp(exp3)
        c.expectType(expected = arrayType, actual = tpe1, exp1.loc)
        c.expectType(expected = Type.Int32, actual = tpe2, exp2.loc)
        c.expectType(expected = elmVar, actual = tpe3, exp3.loc)
        c.unifyType(evar, Type.mkUnion(regionVar, eff1, eff2, eff3, loc), loc)
        val resTpe = Type.Unit
        val resEff = evar
        (resTpe, resEff)

      case Expr.ArrayLength(exp, evar, loc) =>
        val elmVar = freshVar(Kind.Star, loc)
        val regionVar = freshVar(Kind.Eff, loc)
        val (tpe, eff) = visitExp(exp)
        c.expectType(Type.mkArray(elmVar, regionVar, loc), tpe, exp.loc)
        c.unifyType(evar, eff, loc)
        val resTpe = Type.Int32
        val resEff = evar
        (resTpe, resEff)

      case Expr.StructNew(sym, fields, region, tvar, evar, loc) =>
        // This case needs to handle expressions like `new S { f = rhs } @ r` where `f` was not present in the struct declaration
        // Here, we check that `rhs` is itself valid by visiting it but make sure not to unify it with anything
        val (instantiatedFieldTpes, structTpe, regionVar) = instantiateStruct(sym, root.structs)
        val visitedFields = fields.map { case (_, v) => visitExp(v) }
        val (regionTpe, regionEff) = visitExp(region)
        val (fieldTpes, fieldEffs) = visitedFields.unzip
        c.unifyType(tvar, structTpe, loc)
        for {
          ((fieldSymUse, expr), fieldTpe1) <- fields.zip(fieldTpes)
        } {
          instantiatedFieldTpes.get(fieldSymUse.sym) match {
            case None => () // if not an actual field, there is nothing to unify
            case Some((_, fieldTpe2)) => c.unifyType(fieldTpe1, fieldTpe2, expr.loc)
          }
        }
        c.unifyType(Type.mkRegionToStar(regionVar, loc), regionTpe, region.loc)
        c.unifyType(evar, Type.mkUnion(fieldEffs :+ regionEff :+ regionVar, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.StructGet(exp, symUse, tvar, evar, loc) =>
        val (instantiatedFieldTpes, structTpe, regionVar) = instantiateStruct(symUse.sym.structSym, root.structs)
        val (tpe, eff) = visitExp(exp)
        c.expectType(structTpe, tpe, exp.loc)
        val (mutable, fieldTpe) = instantiatedFieldTpes(symUse.sym)
        c.unifyType(fieldTpe, tvar, loc)
        // If the field is mutable, then it emits a region effect, otherwise not.
        val accessEffect = if (mutable) regionVar else Type.mkPure(loc)
        c.unifyType(Type.mkUnion(eff, accessEffect, loc), evar, exp.loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.StructPut(exp1, symUse, exp2, tvar, evar, loc) =>
        val (instantiatedFieldTpes, structTpe, regionVar) = instantiateStruct(symUse.sym.structSym, root.structs)
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        c.expectType(structTpe, tpe1, exp1.loc)
        val (_, fieldTpe) = instantiatedFieldTpes(symUse.sym)
        c.expectType(fieldTpe, tpe2, exp2.loc)
        c.unifyType(Type.mkUnit(loc), tvar, loc)
        c.unifyType(Type.mkUnion(eff1, eff2, regionVar, loc), evar, loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.VectorLit(exps, tvar, evar, loc) =>
        val (tpes, effs) = exps.map(visitExp).unzip
        c.unifyAllTypes(tpes, loc)
        val tpe = tpes.headOption.getOrElse(freshVar(Kind.Star, loc))
        c.unifyType(tvar, Type.mkVector(tpe, loc), loc)
        c.unifyType(evar, Type.mkUnion(effs, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.VectorLoad(exp1, exp2, tvar, evar, loc) =>
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        c.expectType(expected = Type.mkVector(tvar, loc), actual = tpe1, exp1.loc)
        c.expectType(expected = Type.Int32, actual = tpe2, exp2.loc)
        c.unifyType(evar, Type.mkUnion(eff1, eff2, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.VectorLength(exp, loc) =>
        val elmVar = freshVar(Kind.Star, loc)
        val (tpe, eff) = visitExp(exp)
        c.expectType(Type.mkVector(elmVar, loc), tpe, exp.loc)
        val resTpe = Type.Int32
        val resEff = eff
        (resTpe, resEff)

      case Expr.Ascribe(exp, expectedTpe, expectedEff, tvar, loc) =>
        // An ascribe expression is sound; the type system checks that the declared type matches the inferred type.
        val (actualTpe, actualEff) = visitExp(exp)
        expectedTpe.foreach { tpe => c.expectType(expected = tpe, actual = actualTpe, loc) }
        c.unifyType(actualTpe, tvar, loc)
        expectedEff.foreach { eff => c.expectType(expected = eff, actual = actualEff, loc) }
        val resTpe = tvar
        val resEff = actualEff
        (resTpe, resEff)

      case Expr.InstanceOf(exp, _, _) =>
        val (_, eff) = visitExp(exp)
        val resTpe = Type.Bool
        val resEff = eff
        (resTpe, resEff)

      case Expr.CheckedCast(cast, exp, tvar, evar, loc) =>
        // A cast expression is sound; the type system ensures the declared type is correct.
        cast match {
          case CheckedCastType.TypeCast =>
            // We replace the type with a fresh variable to allow any type.
            // The validity of this cast is checked in the Safety phase.
            val (_, eff) = visitExp(exp)
            c.unifyType(evar, eff, loc)
            val resTpe = tvar
            val resEff = evar
            (resTpe, resEff)

          case CheckedCastType.EffectCast =>
            // We union the effect with a fresh variable to allow unifying with a "larger" effect.
            val (tpe, eff) = visitExp(exp)
            c.unifyType(tvar, tpe, loc)
            val resTpe = tvar

            // Optimization: Check if subeffecting is enabled.
            val resEff = if (flix.options.xsubeffecting == Set(Subeffecting.ModDefs, Subeffecting.InsDefs, Subeffecting.Lambdas)) {
              // If all subeffecting options are enabled then we skip the fresh effect variable.
              eff
            } else {
              // Otherwise we use the fresh effect variable.
              Type.mkUnion(eff, evar, loc)
            }
            (resTpe, resEff)
        }

      case Expr.UncheckedCast(exp, declaredTpe, declaredEff, tvar, loc) =>
        // An unchecked cast expression is unsound; the type system assumes the declared type and effect are correct.
        val (actualTyp, actualEff) = visitExp(exp)
        c.unifyType(tvar, declaredTpe.getOrElse(actualTyp), loc)
        val resTpe = tvar
        val resEff = declaredEff.getOrElse(actualEff)
        (resTpe, resEff)

      case Expr.Unsafe(exp, eff0, loc) =>
        val (tpe, eff) = visitExp(exp)
        val resTpe = tpe
        val resEff = Type.mkDifference(eff, eff0, loc)
        (resTpe, resEff)

      case Expr.Without(exp, symUse, _) =>
        //
        // e: tpe \ eff - symUse
        // -------------------------
        // e without symUse : tpe
        //
        val (tpe, eff) = visitExp(exp)
        val effWithoutSym = Type.mkDifference(eff, Type.Cst(TypeConstructor.Effect(symUse.sym, Kind.Eff), symUse.qname.loc), symUse.qname.loc) // TODO EFF-TPARAMS need kind
        c.unifyType(eff, effWithoutSym, symUse.qname.loc)
        val resTpe = tpe
        val resEff = eff
        (resTpe, resEff)

      case Expr.TryCatch(exp, rules, loc) =>
        val (tpe, eff) = visitExp(exp)
        val (tpes, effs) = rules.map(visitCatchRule).unzip
        c.unifyAllTypes(tpes, loc)
        val ruleTpe = tpes.headOption.getOrElse(freshVar(Kind.Star, loc))
        c.unifyType(tpe, ruleTpe, loc)
        val resTpe = tpe
        val resEff = Type.mkUnion(eff :: effs, loc)
        (resTpe, resEff)

      case KindedAst.Expr.Throw(exp, tvar, evar, loc) =>
        val (_, eff) = visitExp(exp)
        c.unifyType(evar, Type.mkUnion(eff, Type.IO, loc), loc)
        val resultTpe = tvar
        val resultEff = evar
        (resultTpe, resultEff)

      case Expr.Handler(symUse, rules, tvar, evar1, evar2, loc) =>
        //
        // ∀i. Γ, opix1: opit1, .., ki: opit -> t \ k_ef ⊢ ei: t \ ei_ef
        //     k_ef = (ef - Eff) ∪ (∪_i ei_ef)
        // ---------------------------------------------------------------------
        // Γ ⊢ handler Eff {
        //   def op1(op1x1, .., k1) = e1
        //   def op2(op2x1, .., k2) = e2
        //   ..
        // }: (Unit -> t \ ef) -> t \ k_ef
        //
        // where:
        // eff Eff {
        //  def op1(op1x1: op1t1, ..): op1t
        //  def op2(op2x1: op2t2, ..): op2t
        //  ..
        // }
        //
        val (tpes, effs) = rules.map(visitHandlerRule(_, tvar, evar2)).unzip
        c.unifyAllTypes(tvar :: tpes, loc)

        val handledEffect = Type.Cst(TypeConstructor.Effect(symUse.sym, Kind.Eff), symUse.qname.loc) // TODO EFF-TPARAMS need kind
        // Subtract the effect from the body effect and add the handler effects.
        val continuationEffect = Type.mkUnion(Type.mkDifference(evar1, handledEffect, symUse.qname.loc), Type.mkUnion(effs, loc), loc)
        c.unifyType(evar2, continuationEffect, loc)
        val resultTpe = Type.mkArrowWithEffect(Type.mkArrowWithEffect(Type.Unit, evar1, tvar, loc), evar2, tvar, loc)
        val resultEff = Type.Pure
        (resultTpe, resultEff)

      case Expr.RunWith(exp1, exp2, tvar, evar, loc) =>
        val (tpe, eff) = visitExp(exp1)
        val (handlerTpe, handlerExpEff) = visitExp(exp2)
        val handlerArg = Type.mkArrowWithEffect(Type.Unit, eff, tpe, loc.asSynthetic)
        c.unifyType(Type.mkArrowWithEffect(handlerArg, evar, tvar, loc.asSynthetic), handlerTpe, loc)
        val resultTpe = tvar
        val resultEff = Type.mkUnion(evar, handlerExpEff, loc.asSynthetic)
        (resultTpe, resultEff)

      case Expr.InvokeConstructor(clazz, exps, jvar, evar, loc) =>
        // Γ ⊢ eᵢ ... : τ₁ ...    Γ ⊢ ι ~ JvmConstructor(k, eᵢ ...)
        // --------------------------------------------------------
        // Γ ⊢ new k(e₁ ...) : k \ JvmToEff[ι]
        val baseEff = Type.JvmToEff(jvar, loc)
        val clazzTpe = Type.getFlixType(clazz)
        val (tpes, effs) = exps.map(visitExp).unzip
        c.unifyType(jvar, Type.UnresolvedJvmType(Type.JvmMember.JvmConstructor(clazz, tpes), loc), loc)
        c.unifyType(evar, Type.mkUnion(baseEff :: effs, loc), loc)
        val resTpe = clazzTpe
        val resEff = evar
        (resTpe, resEff)

      case Expr.InvokeMethod(exp, methodName, exps, jvar, tvar, evar, loc) =>
        // Γ ⊢ e : τ    Γ ⊢ eᵢ ... : τ₁ ...    Γ ⊢ ι ~ JvmMethod(τ, m, τᵢ ...)
        // ---------------------------------------------------------------
        // Γ ⊢ e.m(eᵢ ...) : JvmToType[ι] \ JvmToEff[ι]
        val baseEff = Type.JvmToEff(jvar, loc)
        val (tpe, eff) = visitExp(exp)
        val (tpes, effs) = exps.map(visitExp).unzip
        c.unifyType(jvar, Type.UnresolvedJvmType(Type.JvmMember.JvmMethod(tpe, methodName, tpes), loc), loc)
        c.unifyType(tvar, Type.JvmToType(jvar, loc), loc)
        c.unifyType(evar, Type.mkUnion(baseEff :: eff :: effs, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.InvokeStaticMethod(clazz, methodName, exps, jvar, tvar, evar, loc) =>
        // Γ ⊢ eᵢ ... : τ₁ ...    Γ ⊢ ι ~ JvmStaticMethod(m, τᵢ ...)
        // ---------------------------------------------------------------
        // Γ ⊢ m(eᵢ ...) : JvmToType[ι] \ JvmToEff[ι]
        val baseEff = Type.JvmToEff(jvar, loc)
        val (tpes, effs) = exps.map(visitExp).unzip
        c.unifyType(jvar, Type.UnresolvedJvmType(Type.JvmMember.JvmStaticMethod(clazz, methodName, tpes), loc), loc)
        c.unifyType(tvar, Type.JvmToType(jvar, loc), loc)
        c.unifyType(evar, Type.mkUnion(baseEff :: effs, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.GetField(exp, fieldName, jvar, tvar, evar, loc) =>
        // Γ ⊢ e : τ    Γ ⊢ ι ~ JvmFieldMethod(τ, m)
        // ---------------------------------------------------------------
        // Γ ⊢ e.f : JvmToType[ι]
        val (tpe, eff) = visitExp(exp)
        c.unifyType(jvar, Type.UnresolvedJvmType(Type.JvmMember.JvmField(exp.loc, tpe, fieldName), loc), loc)
        c.unifyType(tvar, Type.JvmToType(jvar, loc), loc) // unify field type
        c.unifyType(evar, Type.mkUnion(Type.IO :: eff :: Nil, loc), loc) // unify effects
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.PutField(field, clazz, exp1, exp2, loc) =>
        val fieldType = Type.getFlixType(field.getType)
        val classType = Type.getFlixType(clazz)
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        c.expectType(expected = classType, actual = tpe1, exp1.loc)
        c.expectType(expected = fieldType, actual = tpe2, exp2.loc)
        val resTpe = Type.Unit
        val resEff = Type.mkUnion(eff1, eff2, Type.IO, loc)
        (resTpe, resEff)

      case Expr.GetStaticField(field, _) =>
        val fieldType = Type.getFlixType(field.getType)
        val resTpe = fieldType
        val resEff = Type.IO
        (resTpe, resEff)

      case Expr.PutStaticField(field, exp, loc) =>
        val (valueTyp, eff) = visitExp(exp)
        c.expectType(expected = Type.getFlixType(field.getType), actual = valueTyp, exp.loc)
        val resTpe = Type.Unit
        val resEff = Type.mkUnion(eff, Type.IO, loc)
        (resTpe, resEff)

      case Expr.NewObject(_, clazz, methods, _) =>
        methods.foreach(visitJvmMethod)
        val resTpe = Type.getFlixType(clazz)
        val resEff = Type.IO
        (resTpe, resEff)

      case Expr.NewChannel(exp, tvar, loc) =>
        val elmTpe = freshVar(Kind.Star, loc)
        val (tpe, eff) = visitExp(exp)
        c.expectType(expected = Type.Int32, actual = tpe, exp.loc)
        c.unifyType(tvar, Type.mkTuple(List(Type.mkSender(elmTpe, loc), Type.mkReceiver(elmTpe, loc)), loc), loc)
        val resTpe = tvar
        val resEff = Type.mkUnion(eff, Type.Chan, loc)
        (resTpe, resEff)

      case Expr.GetChannel(exp, tvar, evar, loc) =>
        val elmTpe = freshVar(Kind.Star, loc)
        val receiverTpe = Type.mkReceiver(elmTpe, loc)
        val (tpe, eff) = visitExp(exp)
        c.expectType(expected = receiverTpe, actual = tpe, exp.loc)
        c.unifyType(tvar, elmTpe, loc)
        c.unifyType(evar, Type.mkUnion(eff, Type.Chan, Type.NonDet, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.PutChannel(exp1, exp2, evar, loc) =>
        val elmTpe = freshVar(Kind.Star, loc)
        val senderTpe = Type.mkSender(elmTpe, loc)
        val (tpe1, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        c.expectType(expected = senderTpe, actual = tpe1, exp1.loc)
        c.expectType(expected = elmTpe, actual = tpe2, exp2.loc)
        c.unifyType(evar, Type.mkUnion(eff1, eff2, Type.Chan, loc), loc)
        val resTpe = Type.mkUnit(loc)
        val resEff = evar
        (resTpe, resEff)

      case Expr.SelectChannel(rules, default, tvar, evar, loc) =>
        val (ruleTypes, ruleEffs) = rules.map(visitSelectRule).unzip
        val (defaultType, eff2) = visitDefaultRule(default, loc)
        c.unifyAllTypes(tvar :: defaultType :: ruleTypes, loc)
        c.unifyType(evar, Type.mkUnion(eff2 :: ruleEffs, loc), loc)
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)

      case Expr.Spawn(exp1, exp2, loc) =>
        // Γ ⊢ e1 : τ \ ef1 ∩ prims      Γ ⊢ e2 : Region[r] \ ef2
        // --------------------------------------------------------
        // Γ ⊢ spawn e1 @ e2 : Unit \ (ef1 ∩ prims) ∪ ef2
        val regionVar = freshVar(Kind.Eff, loc)
        val regionType = Type.mkRegionToStar(regionVar, loc)
        val anyEff = freshVar(Kind.Eff, loc)
        val (_, eff1) = visitExp(exp1)
        val (tpe2, eff2) = visitExp(exp2)
        c.unifyType(eff1, Type.mkIntersection(anyEff, Type.PrimitiveEffs, loc), exp1.loc)
        c.expectType(expected = regionType, actual = tpe2, exp2.loc)
        val resTpe = Type.Unit
        // `regionVar` should be included but is omitted to allow spawn nesting.
        val resEff = Type.mkUnion(eff1, eff2, loc)
        (resTpe, resEff)

      case Expr.ParYield(frags, exp, _) =>
        // We don't need to keep the types of the fragments
        // since they are all bound to the patterns

        // The result effect is only the effect of exp
        // because the fragments must all be pure.
        frags.foreach(visitParYieldFragment)
        val (tpe, eff) = visitExp(exp)
        val resTpe = tpe
        val resEff = eff
        (resTpe, resEff)

      case Expr.Lazy(exp, loc) =>
        val (tpe, eff) = visitExp(exp)
        c.expectType(expected = Type.Pure, actual = eff, exp.loc)
        val resTpe = Type.mkLazy(tpe, loc)
        val resEff = Type.Pure
        (resTpe, resEff)

      case Expr.Force(exp, tvar, loc) =>
        val (tpe, eff) = visitExp(exp)
        c.expectType(expected = Type.mkLazy(tvar, loc), actual = tpe, exp.loc)
        val resTpe = tvar
        val resEff = eff
        (resTpe, resEff)

      case e: Expr.FixpointConstraintSet => SchemaConstraintGen.visitFixpointConstraintSet(e)
      case e: Expr.FixpointLambda => SchemaConstraintGen.visitFixpointLambda(e)
      case e: Expr.FixpointMerge => SchemaConstraintGen.visitFixpointMerge(e)
      case e: Expr.FixpointQueryWithProvenance => SchemaConstraintGen.visitFixpointQueryWithProvenance(e)
      case e: Expr.FixpointSolve => SchemaConstraintGen.visitFixpointSolve(e)
      case e: Expr.FixpointFilter => SchemaConstraintGen.visitFixpointFilter(e)
      case e: Expr.FixpointInject => SchemaConstraintGen.visitFixpointInject(e)
      case e: Expr.FixpointProject => SchemaConstraintGen.visitFixpointProject(e)

      case Expr.Error(_, tvar, evar) =>
        // The error expression has whatever type and effect it needs to have.
        val resTpe = tvar
        val resEff = evar
        (resTpe, resEff)
    }
  }

  /**
    * Generates constraints for the pattern.
    *
    * Returns the pattern's type. The type may be a variable which must later be resolved.
    */
  def visitPattern(pat0: KindedAst.Pattern)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): Type = {
    implicit val scope: Scope = c.getScope
    pat0 match {
      case KindedAst.Pattern.Wild(tvar, _) => tvar

      case KindedAst.Pattern.Var(sym, tvar, loc) =>
        c.unifyType(sym.tvar, tvar, loc)
        tvar

      case KindedAst.Pattern.Cst(cst, _) => Type.constantType(cst)

      case KindedAst.Pattern.Tag(symUse, pats, tvar, loc) =>
        val decl = root.enums(symUse.sym.enumSym)
        val caze = decl.cases(symUse.sym)
        // We ignore constraints as tag schemes do not have them
        val (_, _, tagType, _) = Scheme.instantiate(caze.sc, loc.asSynthetic)

        // The tag type is a function from the type of variant to the type of the enum.
        val tpes = pats.map(visitPattern)
        val constructorBase = if (tpes.nonEmpty) Type.mkPureUncurriedArrow(tpes, tvar, loc) else tvar
        c.unifyType(tagType, constructorBase, loc)
        tvar

      case KindedAst.Pattern.Tuple(elms, loc) =>
        val tpes = elms.map(visitPattern)
        Type.mkTuple(tpes, loc)

      case KindedAst.Pattern.Record(pats, pat, tvar, loc) =>
        val freshRowVar = freshVar(Kind.RecordRow, loc)
        val freshRecord = Type.mkRecord(freshRowVar, loc.asSynthetic)

        val tailTpe = visitPattern(pat)
        c.unifyType(freshRecord, tailTpe, loc.asSynthetic)
        val patTpes = pats.map(visitRecordLabelPattern)
        val resTpe = mkRecordType(patTpes, freshRowVar, loc)
        c.unifyType(resTpe, tvar, loc)
        resTpe

      case KindedAst.Pattern.Error(tvar, _) => tvar

    }
  }

  /**
    * Generates constraints for the patterns inside the record label pattern.
    *
    * Returns the type of the pattern.
    */
  private def visitExtPattern(pat0: KindedAst.ExtPattern)(implicit c: TypeContext): Type = pat0 match {
    case ExtPattern.Wild(tvar, _) => tvar

    case ExtPattern.Var(sym, tvar, loc) =>
      c.unifyType(sym.tvar, tvar, loc)
      tvar

    case ExtPattern.Error(tvar, _) => tvar
  }

  /**
    * Generates constraints for the patterns inside the record label pattern.
    *
    * Returns the label, pattern type, and location of the pattern.
    */
  private def visitRecordLabelPattern(pat: KindedAst.Pattern.Record.RecordLabelPattern)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): (Name.Label, Type, SourceLocation) = pat match {
    case KindedAst.Pattern.Record.RecordLabelPattern(label, p, tvar, loc) =>
      // { Label = Pattern ... }
      val tpe = visitPattern(p)
      c.unifyType(tpe, tvar, loc)
      (label, tpe, loc)
  }

  /**
    * Generates constraints for the given match rule.
    *
    * Returns the pattern type, the body's type, and the body's effect
    */
  private def visitMatchRule(rule: KindedAst.MatchRule)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): (Type, Type, Type) = rule match {
    case KindedAst.MatchRule(pat, guard, exp, _) =>
      val patTpe = visitPattern(pat)
      guard.foreach {
        g =>
          val (guardTpe, guardEff) = visitExp(g)
          c.expectType(expected = Type.Bool, actual = guardTpe, g.loc)
          c.expectType(expected = Type.Pure, actual = guardEff, g.loc)
      }
      val (tpe, eff) = visitExp(exp)
      (patTpe, tpe, eff)
  }

  /**
    * Generates constraints for the given extensible match rule.
    *
    * Returns the name of the constructor, the types of the constructor, the type of the expression body, and its effect.
    */
  private def visitExtMatchRule(rule: KindedAst.ExtMatchRule)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): ((Name.Pred, List[Type]), Type, Type) = rule match {
    case KindedAst.ExtMatchRule(label, pats, exp, _) =>
      val name = Name.Pred(label.name, label.loc)
      val patTypes = pats.map(visitExtPattern)
      val (tpe, eff) = visitExp(exp)
      ((name, patTypes), tpe, eff)
  }

  /**
    * Generates constraints for the given typematch rule.
    *
    * Returns the body's type and the body's effect
    */
  private def visitTypeMatchRule(rule: KindedAst.TypeMatchRule)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): (Type, Type) = rule match {
    case KindedAst.TypeMatchRule(sym, declTpe, exp, _) =>
      // We mark all the type vars in the declared type as rigid.
      // This ensures we get a substitution from the actual type to the declared type.
      // This marking only really affects wildcards,
      // as non-wildcard variables must come from the function signature
      // and are therefore already rigid.
      declTpe.typeVars.map(_.sym).foreach(c.rigidify)

      // Unify the variable's type with the declared type
      c.unifyType(sym.tvar, declTpe, sym.loc)

      visitExp(exp)
  }

  /**
    * Generates constraints for the given catch rule.
    *
    * Returns the the body's type and the body's effect
    */
  private def visitCatchRule(rule: KindedAst.CatchRule)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): (Type, Type) = rule match {
    case KindedAst.CatchRule(sym, clazz, exp, _) =>
      c.expectType(expected = Type.mkNative(clazz, sym.loc), sym.tvar, sym.loc)
      visitExp(exp)
  }

  /**
    * Generates constraints unifying the given expected and actual formal parameters.
    */
  private def unifyFormalParams(op: Symbol.OpSym, expected: List[KindedAst.FormalParam], actual: List[KindedAst.FormalParam])(implicit c: TypeContext): Unit = {
    // length check done in Resolver
    c.expectTypeArguments(op, expectedTypes = expected.map(_.tpe), actualTypes = actual.map(_.tpe), actual.map(_.loc))
  }

  /**
    * Generates constraints for the given handler rule.
    *
    * Returns the body's type and the body's effect.
    *
    * @param tryBlockTpe        the type of the try-block associated with the handler
    * @param continuationEffect the effect of the continuation
    */
  private def visitHandlerRule(rule: KindedAst.HandlerRule, tryBlockTpe: Type, continuationEffect: Type)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): (Type, Type) = rule match {
    case KindedAst.HandlerRule(symUse, actualFparams0, body, opTvar, loc) =>
      val effect = root.effects(symUse.sym.eff)
      val ops = effect.ops.map(op => op.sym -> op).toMap
      // Don't need to generalize since ops are monomorphic
      // Don't need to handle unknown op because resolver would have caught this
      val (actualFparams, List(resumptionFparam)) = actualFparams0.splitAt(actualFparams0.length - 1)
      ops(symUse.sym) match {
        case KindedAst.Op(_, KindedAst.Spec(_, _, _, _, expectedFparams, _, opTpe, _, _, _), _) =>
          val resumptionArgType = opTpe
          val resumptionResType = tryBlockTpe
          val resumptionEff = continuationEffect
          val expectedResumptionType = Type.mkArrowWithEffect(resumptionArgType, resumptionEff, resumptionResType, loc.asSynthetic)
          unifyFormalParams(symUse.sym, expected = expectedFparams, actual = actualFparams)
          c.expectType(expected = expectedResumptionType, actual = resumptionFparam.tpe, resumptionFparam.loc)
          val (actualTpe, actualEff) = visitExp(body)

          // unify the operation return type with its tvar
          c.unifyType(actualTpe, opTvar, body.loc)

          (actualTpe, actualEff)
      }
  }

  /**
    * Generates constraints for the JVM method.
    */
  private def visitJvmMethod(method: KindedAst.JvmMethod)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): Unit = method match {
    case KindedAst.JvmMethod(_, fparams, exp, returnTpe, eff, _) =>

      /**
        * Constrains the given formal parameter to its declared type.
        */
      def visitFormalParam(fparam: KindedAst.FormalParam): Unit = fparam match {
        case KindedAst.FormalParam(sym, _, tpe, _, loc) =>
          c.unifyType(sym.tvar, tpe, loc)
      }

      fparams.foreach(visitFormalParam)
      val (bodyTpe, bodyEff) = visitExp(exp)
      c.expectType(expected = returnTpe, actual = bodyTpe, exp.loc)
      c.expectType(expected = eff, actual = bodyEff, exp.loc)
  }

  /**
    * Generates constraints for the SelectChannelRule.
    *
    * Returns the type and effect of the rule.
    */
  private def visitSelectRule(sr0: KindedAst.SelectChannelRule)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): (Type, Type) = {
    sr0 match {
      case KindedAst.SelectChannelRule(sym, chan, body, _) =>
        val (chanType, eff1) = visitExp(chan)
        val (bodyType, eff2) = visitExp(body)
        c.unifyType(chanType, Type.mkReceiver(sym.tvar, sym.loc), sym.loc)
        val resTpe = bodyType
        val resEff = Type.mkUnion(eff1, eff2, Type.Chan, Type.NonDet, body.loc)
        (resTpe, resEff)
    }
  }

  /**
    * Generates constraints for the default rule.
    *
    * Returns the type and effect of the rule body.
    */
  private def visitDefaultRule(exp0: Option[KindedAst.Expr], loc: SourceLocation)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): (Type, Type) = {
    implicit val scope: Scope = c.getScope
    exp0 match {
      case None => (freshVar(Kind.Star, loc), Type.Pure)
      case Some(exp) => visitExp(exp)
    }
  }

  /**
    * Generates constraints for the given ParYieldFragment.
    */
  private def visitParYieldFragment(frag: KindedAst.ParYieldFragment)(implicit c: TypeContext, root: KindedAst.Root, flix: Flix): Unit = frag match {
    case KindedAst.ParYieldFragment(pat, exp, loc) =>
      val patTpe = visitPattern(pat)
      val (tpe, eff) = visitExp(exp)
      c.unifyType(patTpe, tpe, loc)
      c.expectType(expected = Type.Pure, actual = eff, exp.loc)
  }

  /**
    * Builds a record type from the given fields and rest type.
    *
    * The rest type represents the "tail" of the record.
    */
  private def mkRecordType(patTypes: List[(Name.Label, Type, SourceLocation)], rest: Type, loc: SourceLocation): Type = {
    val ps = patTypes.foldRight(rest) {
      case ((lbl, t, l), acc) => Type.mkRecordRowExtend(
        lbl, t, acc, l)
    }
    Type.mkRecord(ps, loc)
  }

  /**
    * Returns the operation corresponding to the given symbol.
    */
  private def lookupOp(sym: Symbol.OpSym, loc: SourceLocation)(implicit root: KindedAst.Root): KindedAst.Op = {
    val eff = root.effects(sym.eff)
    eff.ops.find(_.sym == sym)
      .getOrElse(throw InternalCompilerException(s"Unexpected missing operation $sym in effect ${sym.eff}", loc))
  }

  /**
    * Converts the given type to a free variable if it is `Void`.
    *
    * Otherwise, returns the given type.
    *
    * This is used for operation return types, which cannot be polymorphic.
    */
  private def generalizeVoid(t: Type)(implicit c: TypeContext, flix: Flix): Type = {
    implicit val scope: Scope = c.getScope
    t.typeConstructor match {
      case Some(TypeConstructor.Void) =>
        // The operation type is `Void`. Flix does not have subtyping, but here we want something close to it.
        // Hence, we treat `Void` as a fresh type variable.
        // An alternative would be to allow empty pattern matches, but that is cumbersome.
        Type.freshVar(Kind.Star, t.loc, VarText.Absent)
      case _ => t
    }
  }

  /**
    * Instantiates the scheme of the struct in corresponding to `sym` in `structs`
    * Returns a map from field name to its instantiated type, the type of the instantiated struct, and the instantiated struct's region variable
    *
    * For example, for the struct `struct S [v, r] { a: v, b: Int32 }` where `v` instantiates to `v'` and `r` instantiates to `r'`
    * The first element of the return tuple would be a map with entries `a -> v'` and `b -> Int32`
    * The second element of the return tuple would be(locations omitted) `Apply(Apply(Cst(Struct(S)), v'), r')`
    * The third element of the return tuple would be `r'`
    */
  private def instantiateStruct(sym: Symbol.StructSym, structs: Map[Symbol.StructSym, KindedAst.Struct])(implicit c: TypeContext, flix: Flix): (Map[Symbol.StructFieldSym, (Boolean, Type)], Type, Type.Var) = {
    implicit val scope: Scope = c.getScope
    val struct = structs(sym)
    assert(struct.tparams.last.sym.kind == Kind.Eff)
    val fields = struct.fields
    val (_, _, tpe, substMap) = Scheme.instantiate(struct.sc, struct.loc)
    val subst = Substitution(substMap)
    val instantiatedFields = fields.map {
      case KindedAst.StructField(mod, fieldSym, fieldTpe, _) =>
        fieldSym -> (mod.isMutable, subst(fieldTpe))
    }
    (instantiatedFields.toMap, tpe, substMap(struct.tparams.last.sym))
  }

  /** Returns a fresh variable with a synthetic location. */
  private def freshVar(k: Kind, loc: SourceLocation)(implicit scope: Scope, flix: Flix): Type.Var =
    Type.freshVar(k, loc.asSynthetic)
}
