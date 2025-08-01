/*
 *  Copyright 2020 Magnus Madsen
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package ca.uwaterloo.flix.language.phase.unification

import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol}
import ca.uwaterloo.flix.language.phase.unification.SetFormula.VarOrCase
import ca.uwaterloo.flix.util.InternalCompilerException
import ca.uwaterloo.flix.util.collection.Bimap

/**
  * Companion object for the [[CaseSetSubstitution]] class.
  */
object CaseSetSubstitution {
  /**
    * Returns the empty substitution.
    */
  def empty: CaseSetSubstitution = CaseSetSubstitution(Map.empty)

  /**
    * Returns the singleton substitution mapping the type variable `x` to `tpe`.
    */
  def singleton(x: Int, f: SetFormula): CaseSetSubstitution = {
    // Ensure that we do not add any x -> x mappings.
    f match {
      case y: SetFormula.Var if x == y.x => empty
      case _ => CaseSetSubstitution(Map(x -> f))
    }
  }

}

/**
  * A substitution is a map from type variables to types.
  */
case class CaseSetSubstitution(m: Map[Int, SetFormula]) {

  /**
    * Returns `true` if `this` is the empty substitution.
    */
  val isEmpty: Boolean = m.isEmpty

  /**
    * Applies `this` substitution to the given type `tpe0`.
    */
  def apply(f: SetFormula)(implicit univ: Set[Int]): SetFormula = {
    // Optimization: Return the type if the substitution is empty. Otherwise visit the type.
    if (isEmpty) {
      f
    } else {
      SetFormula.map(f)(m.withDefault(i => SetFormula.Var(i)))(univ)
    }
  }

  /**
    * Applies `this` substitution to the given types `ts`.
    */
  def apply(ts: List[SetFormula])(implicit univ: Set[Int]): List[SetFormula] =
    if (isEmpty) ts else ts.map(apply(_))

  /**
    * Returns the left-biased composition of `this` substitution with `that` substitution.
    */
  def ++(that: CaseSetSubstitution): CaseSetSubstitution = {
    if (this.isEmpty) {
      that
    } else if (that.isEmpty) {
      this
    } else {
      CaseSetSubstitution(
        this.m ++ that.m.filter(kv => !this.m.contains(kv._1))
      )
    }
  }

  /**
    * Returns the composition of `this` substitution with `that` substitution.
    */
  def @@(that: CaseSetSubstitution)(implicit univ: Set[Int]): CaseSetSubstitution = {
    // Case 1: Return `that` if `this` is empty.
    if (this.isEmpty) {
      return that
    }

    // Case 2: Return `this` if `that` is empty.
    if (that.isEmpty) {
      return this
    }

    // Case 3: Merge the two substitutions.

    // NB: Use of mutability improve performance.
    import scala.collection.mutable
    val newBoolAlgebraMap = mutable.Map.empty[Int, SetFormula]

    // Add all bindings in `that`. (Applying the current substitution).
    for ((x, t) <- that.m) {
      newBoolAlgebraMap.update(x, this.apply(t))
    }

    // Add all bindings in `this` that are not in `that`.
    for ((x, t) <- this.m) {
      if (!that.m.contains(x)) {
        newBoolAlgebraMap.update(x, t)
      }
    }

    CaseSetSubstitution(newBoolAlgebraMap.toMap)
  }

  /**
    * Converts this formula substitution into a type substitution
    */
  def toTypeSubstitution(sym: Symbol.RestrictableEnumSym, env: Bimap[SetFormula.VarOrCase, Int]): Substitution = {
    val map = m.map {
      case (k0, v0) =>
        val k = env.getBackward(k0).getOrElse(throw InternalCompilerException(s"missing key $k0", SourceLocation.Unknown)) match {
          case VarOrCase.Var(varSym) => varSym
          case VarOrCase.Case(_) => throw InternalCompilerException("unexpected substituted case", SourceLocation.Unknown)
        }
        val v = SetFormula.toCaseType(v0, sym, env, SourceLocation.Unknown)
        (k, v)
    }
    Substitution(map)
  }
}
