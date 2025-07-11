/*
 * Copyright 2024 Matthew Lutze
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

import ca.uwaterloo.flix.language.ast.shared.{EqualityConstraint, Scope, TraitConstraint}
import ca.uwaterloo.flix.language.ast.{Kind, RigidityEnv, SourceLocation, Symbol, Type}
import ca.uwaterloo.flix.language.phase.typer.TypeConstraint.Provenance
import ca.uwaterloo.flix.util.InternalCompilerException

import scala.collection.mutable

/**
  * The type context is a mutable environment that tracks information during type constraint generation.
  *
  * It maintains a stack of information: Whenever inference enters a region, the current information is pushed
  * onto the stack, and a new empty scope is entered. On exiting a region, the information is popped off, and
  * a special constraint is added which incorporates all the popped constraints.
  */
class TypeContext {

  private object ScopeConstraints {
    /**
      * Creates an empty ScopeConstraints with no associated region.
      *
      * Note: The function must return a _NEW_ object because each object has mutable state.
      */
    def empty: ScopeConstraints = new ScopeConstraints(Scope.Top)

    /**
      * Creates an empty ScopeConstraints associated with the given region.
      */
    def emptyForScope(s: Scope): ScopeConstraints = new ScopeConstraints(s)
  }

  /**
    * Stores typing information relating to a particular region scope.
    */
  private class ScopeConstraints(val scope: Scope) {

    /**
      * The constraints generated for the scope.
      *
      * Note: The initial size of the array buffer has been determined by careful profiling.
      */
    private val constrs: mutable.ArrayBuffer[TypeConstraint] = new mutable.ArrayBuffer(initialSize = 48)

    /**
      * Adds the given constraint to the constraint set.
      */
    def add(constr: TypeConstraint): Unit = this.constrs.append(constr)

    /**
      * Adds all the given constraints to the constraint set.
      */
    def addAll(constrs: Iterable[TypeConstraint]): Unit = this.constrs.appendAll(constrs)

    /**
      * Returns the generated constraints.
      */
    def getConstraints: List[TypeConstraint] = this.constrs.toList
  }

  /**
    * The current scope of constraints.
    */
  private var currentScopeConstraints: ScopeConstraints = ScopeConstraints.empty

  /**
    * The current rigidity environment.
    *
    * This environment only grows; we don't remove rigid variables as we exit a region.
    *
    * We use a mutable variable because RigidityEnv is an immutable structure.
    */
  private var renv: RigidityEnv = RigidityEnv.empty

  /**
    * The typing context from outside the current scope.
    *
    * We push and pop information from this stack when we enter and exit regions.
    */
  private val constraintStack: mutable.Stack[ScopeConstraints] = mutable.Stack.empty

  /**
    * Returns the current rigidity environment.
    */
  def getRigidityEnv: RigidityEnv = renv

  /**
    * Returns the current type constraints.
    */
  def getTypeConstraints: List[TypeConstraint] = currentScopeConstraints.getConstraints

  /**
    * Returns the current scope.
    */
  def getScope: Scope = currentScopeConstraints.scope

  /**
    * Generates constraints unifying the given types.
    *
    * {{{
    *   tpe1 ~ tpe2
    * }}}
    */
  def unifyType(tpe1: Type, tpe2: Type, loc: SourceLocation): Unit = {
    val constr = TypeConstraint.Equality(tpe1, tpe2, Provenance.Match(tpe1, tpe2, loc))
    currentScopeConstraints.add(constr)
  }

  /**
    * Generates constraints unifying the given types.
    *
    * {{{
    *   tpe1 ~ tpe2
    *   tpe1 ~ tpe3
    * }}}
    */
  def unifyType(tpe1: Type, tpe2: Type, tpe3: Type, loc: SourceLocation): Unit = {
    unifyType(tpe1, tpe2, loc)
    unifyType(tpe1, tpe3, loc)
  }

  /**
    * Generates constraints unifying all the given types.
    *
    * Generates no constraints if the list is empty.
    *
    * Generates no constraints if the list has a single element.
    *
    * {{{
    *   tpe1 ~ tpe2
    *   tpe1 ~ tpe3
    *   ...
    *   tpe1 ~ tpeN
    * }}}
    */
  def unifyAllTypes(tpes: List[Type], loc: SourceLocation): Unit = {
    // For performance, avoid creating a fresh type var if the list is non-empty
    tpes match {
      // Case 1: Nonempty list. Unify everything with the first type.
      case tpe1 :: rest =>
        rest.foreach(unifyType(tpe1, _, loc))
      // Case 2: Empty list. Do nothing.
      case Nil => ()
    }
  }

  /**
    * Generates a constraint with an expected type and an actual type.
    *
    * {{{
    *   expected ~ actual
    * }}}
    */
  def expectType(expected: Type, actual: Type, loc: SourceLocation): Unit = {
    val prov = (expected.kind, actual.kind) match {
      case (Kind.Eff, Kind.Eff) => Provenance.ExpectEffect(expected, actual, loc)
      case _ => Provenance.ExpectType(expected, actual, loc)
    }
    val constr = TypeConstraint.Equality(expected, actual, prov)
    currentScopeConstraints.add(constr)
  }

  /**
    * Generates constraints expecting the given type arguments to unify.
    *
    * For expected types `tpeE1 ... tpeEN` and actual types `tpeA1 ... tpeAN`, generates:
    *
    * {{{
    *   tpeE1 ~ tpeA1
    *   tpeE2 ~ tpeA2
    *   ...
    *   tpeEN ~ tpeAN
    * }}}
    */
  def expectTypeArguments(sym: Symbol, expectedTypes: List[Type], actualTypes: List[Type], actualLocs: List[SourceLocation]): Unit = {
    expectedTypes.zip(actualTypes).zip(actualLocs).zipWithIndex.foreach {
      case (((expectedType, actualType), loc), index) =>
        val argNum = index + 1
        val prov = Provenance.ExpectArgument(expectedType, actualType, sym, argNum, loc)
        val constr = TypeConstraint.Equality(expectedType, actualType, prov)
        currentScopeConstraints.add(constr)
    }
  }

  /**
   * Generates a constraint representing a source effect.
   *
   * For an effect variable effVar and source effect sourceEff, generates:
   *
   * {{{
   *   effVar ~ sourceEff
   * }}}
   */
  def unifySource(effVar: Type.Var, sourceEff: Type, loc: SourceLocation): Unit = {
    val constr = TypeConstraint.Equality(effVar, sourceEff, Provenance.Source(effVar, sourceEff, loc))
    currentScopeConstraints.add(constr)
  }

  /**
    * Adds the given trait constraints to the context.
    */
  def addClassConstraints(tconstrs0: List[TraitConstraint], loc: SourceLocation): Unit = {
    // convert all the syntax-level constraints to semantic constraints
    val tconstrs = tconstrs0.map {
      case TraitConstraint(head, arg, _) => TypeConstraint.Trait(head.sym, arg, loc)
    }
    currentScopeConstraints.addAll(tconstrs)
  }

  /**
    * Adds the given equality constraints to the context.
    */
  def addEqualityConstraints(econstrs0: List[EqualityConstraint], loc: SourceLocation): Unit = {
    val econstrs = econstrs0.map {
      case EqualityConstraint(symUse, tpe1, tpe2, loc) =>
        val t1 = Type.AssocType(symUse, tpe1, tpe2.kind, loc)
        val t2 = tpe2
        val prov = Provenance.Match(t1, t2, loc)
        TypeConstraint.Equality(t1, t2, prov)
    }
    currentScopeConstraints.addAll(econstrs)
  }

  /**
    * Marks the given type variable as rigid in the context.
    */
  def rigidify(sym: Symbol.KindedTypeVarSym): Unit = {
    renv = renv.markRigid(sym)
  }

  /**
    * Enters a new region.
    *
    * Current scope information is pushed onto the stack,
    * the region symbol is marked as rigid,
    * and we get a fresh empty set of constraints for the new scope.
    */
  def enterRegion(sym: Symbol.RegionSym): Unit = {
    val newScope = currentScopeConstraints.scope.enter(sym)
      // save the info from the parent region
    constraintStack.push(currentScopeConstraints)
    currentScopeConstraints = ScopeConstraints.emptyForScope(newScope)
  }

  /**
    * Exits a region, unifying the external effect with a purified version of the internal effect.
    *
    * We generate a fresh purification constraint:
    *
    * {{{
    *   externalEff1 ~ internalEff2[sym ↦ Pure]
    * }}}
    *
    * Where the `sym` is the symbol of the region we are exiting.
    * All the constraints from the inner region are nested under the new purification constraint.
    * (They must be resolved first for the purification to be valid.)
    *
    * We pop the constraints from the parent scope; these become our current constraints.
    * We add the new purification constraints to the current constraints.
    */
  def exitRegion(externalEff1: Type, internalEff2: Type, loc: SourceLocation): Unit = {
    val constr = currentScopeConstraints.scope match {
      case Scope(Nil) => throw InternalCompilerException("unexpected missing region", loc)
      case Scope(r :: _) =>
        // TODO ASSOC-TYPES improve prov. We can probably get a better prov than "match"
        val prov = Provenance.Match(externalEff1, internalEff2, loc)
        TypeConstraint.Purification(r, externalEff1, internalEff2, prov, currentScopeConstraints.getConstraints)
    }

    currentScopeConstraints = constraintStack.pop()
    currentScopeConstraints.add(constr)
  }

}
