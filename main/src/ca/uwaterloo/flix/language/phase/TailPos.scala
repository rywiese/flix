/*
 * Copyright 2016 Magnus Madsen
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

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.ReducedAst.*
import ca.uwaterloo.flix.language.ast.shared.ExpPosition
import ca.uwaterloo.flix.language.dbg.AstPrinter.DebugReducedAst
import ca.uwaterloo.flix.util.ParOps
import ca.uwaterloo.flix.util.collection.MapOps

/**
  * The TailPos phase identifies function calls and try-with expressions that are in tail position,
  * and marks tail-recursive calls.
  *
  * Specifically, it replaces [[Expr.ApplyDef]] AST nodes with [[Expr.ApplySelfTail]] AST nodes
  * when the [[Expr.ApplyDef]] node calls the enclosing function and occurs in tail position.
  *
  * Otherwise, it adds [[ExpPosition.Tail]] to function calls and try-with expressions in tail
  * position.
  *
  * For correctness it is assumed that all calls in the given AST have [[ExpPosition.NonTail]]
  * and there are no [[Expr.ApplySelfTail]] nodes present.
  */
object TailPos {

  /** Identifies expressions in tail position in `root`. */
  def run(root: Root)(implicit flix: Flix): Root = flix.phase("TailPos") {
    val defns = ParOps.parMapValues(root.defs)(visitDef)
    root.copy(defs = defns)
  }

  /** Identifies expressions in tail position in `defn`. */
  private def visitDef(defn: Def): Def = {
    defn.copy(expr = visitExp(defn.expr)(defn))
  }

  /**
    * Introduces expressions in tail position in `exp0`.
    *
    * Replaces every [[Expr.ApplyDef]] that calls the enclosing function and occurs in tail
    * position with [[Expr.ApplySelfTail]].
    */
  private def visitExp(exp0: Expr)(implicit defn: Def): Expr = exp0 match {
    case Expr.Let(sym, exp1, exp2, tpe, purity, loc) =>
      // `exp2` is in tail position.
      val e2 = visitExp(exp2)
      Expr.Let(sym, exp1, e2, tpe, purity, loc)

    case Expr.Stmt(exp1, exp2, tpe, purity, loc) =>
      // `exp2` is in tail position.
      val e2 = visitExp(exp2)
      Expr.Stmt(exp1, e2, tpe, purity, loc)

    case Expr.IfThenElse(exp1, exp2, exp3, tpe, purity, loc) =>
      // The branches are in tail position.
      val e2 = visitExp(exp2)
      val e3 = visitExp(exp3)
      Expr.IfThenElse(exp1, e2, e3, tpe, purity, loc)

    case Expr.Branch(e0, br0, tpe, purity, loc) =>
      // Each branch is in tail position.
      val br = MapOps.mapValues(br0)(visitExp)
      Expr.Branch(e0, br, tpe, purity, loc)

    case Expr.ApplyClo(exp, exps, _, tpe, purity, loc) =>
      // Mark expression as tail position.
      Expr.ApplyClo(exp, exps, ExpPosition.Tail, tpe, purity, loc)

    case Expr.ApplyDef(sym, exps, _, tpe, purity, loc) =>
      // Check whether this is a self recursive call.
      if (defn.sym != sym) {
        // Mark expression as tail position.
        Expr.ApplyDef(sym, exps, ExpPosition.Tail, tpe, purity, loc)
      } else {
        // Self recursive tail call.
        Expr.ApplySelfTail(sym, exps, tpe, purity, loc)
      }

    case Expr.RunWith(exp, effUse, rules, _, tpe, purity, loc) =>
      // Mark expression as tail position.
      Expr.RunWith(exp, effUse, rules, ExpPosition.Tail, tpe, purity, loc)

    // Expressions that do not need ExpPosition marking and do not have sub-expression in tail
    // position.
    case Expr.ApplyOp(_, _, _, _, _) => exp0
    case Expr.ApplyAtomic(_, _, _, _, _) => exp0
    case Expr.ApplySelfTail(_, _, _, _, _) => exp0
    case Expr.Cst(_, _, _) => exp0
    case Expr.JumpTo(_, _, _, _) => exp0
    case Expr.NewObject(_, _, _, _, _, _) => exp0
    case Expr.Scope(_, _, _, _, _) => exp0
    case Expr.TryCatch(_, _, _, _, _) => exp0
    case Expr.Var(_, _, _) => exp0
  }

}
