package com.findmycar.shared

/**
 * Generic state machine with declarative transition rules.
 *
 * All state logic is defined in one place using a DSL:
 *
 *     val sm = StateMachine<MyState, MyInput>(MyState.INIT) {
 *         state(MyState.INIT) {
 *             transitionTo(MyState.RUNNING) { input -> input.speed > 10 }
 *         }
 *         state(MyState.RUNNING) {
 *             transitionTo(MyState.STOPPED) { input -> input.speed == 0 }
 *         }
 *     }
 *
 *     sm.process(input)  // evaluates rules, transitions if matched
 */
class StateMachine<S : Enum<S>, I>(
    initialState: S,
    builder: StateMachineBuilder<S, I>.() -> Unit
) {
    var currentState: S = initialState
        private set

    private val rules: Map<S, List<TransitionRule<S, I>>>
    private var onTransition: ((from: S, to: S, input: I) -> Unit)? = null

    init {
        val b = StateMachineBuilder<S, I>()
        b.builder()
        rules = b.build()
    }

    /**
     * Process input: evaluate transition rules for current state.
     * If a rule matches, transition to the new state.
     *
     * @return The new state (or same if no transition)
     */
    fun process(input: I): S {
        val stateRules = rules[currentState] ?: return currentState
        for (rule in stateRules) {
            if (rule.condition(input)) {
                val from = currentState
                currentState = rule.target
                onTransition?.invoke(from, currentState, input)
                return currentState
            }
        }
        return currentState
    }

    /**
     * Register a callback for state transitions.
     */
    fun onTransition(handler: (from: S, to: S, input: I) -> Unit) {
        onTransition = handler
    }

    /**
     * Force state (for restoring persisted state).
     */
    fun setState(state: S) {
        currentState = state
    }

    /**
     * Reset to initial state.
     */
    fun reset(initialState: S) {
        currentState = initialState
    }
}

/**
 * Builder DSL for defining state machine rules.
 */
class StateMachineBuilder<S : Enum<S>, I> {
    private val stateRules = mutableMapOf<S, MutableList<TransitionRule<S, I>>>()

    fun state(state: S, block: StateRuleBuilder<S, I>.() -> Unit) {
        val builder = StateRuleBuilder<S, I>(state)
        builder.block()
        stateRules.getOrPut(state) { mutableListOf() }.addAll(builder.rules)
    }

    fun build(): Map<S, List<TransitionRule<S, I>>> = stateRules
}

class StateRuleBuilder<S : Enum<S>, I>(private val from: S) {
    val rules = mutableListOf<TransitionRule<S, I>>()

    fun transitionTo(target: S, condition: (I) -> Boolean) {
        rules.add(TransitionRule(target, condition))
    }
}

data class TransitionRule<S, I>(
    val target: S,
    val condition: (I) -> Boolean
)
