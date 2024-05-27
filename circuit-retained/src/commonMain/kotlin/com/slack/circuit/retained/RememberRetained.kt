// Copyright (C) 2022 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.slack.circuit.retained

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.snapshots.SnapshotMutableState
import androidx.compose.runtime.structuralEqualityPolicy

/**
 * Remember the value produced by [init].
 *
 * It behaves similarly to [remember], but the stored value will survive configuration changes, such
 * as a screen rotation.
 *
 * You can use it with a value stored inside [mutableStateOf].
 *
 * This differs from `rememberSaveable` by not being tied to Android bundles or parcelable. You
 * should take care to ensure that the state computed by [init] does not capture anything that is
 * not safe to persist across reconfiguration, such as Navigators. The same caveats of
 * `rememberSaveable` also still apply (i.e. do not retain Android Contexts, Views, etc).
 *
 * However, it does not participate in saved instance state either, so care should be taken to
 * choose the right retention mechanism for your use case. Consider the below two examples.
 *
 * The first case will retain `state` across configuration changes and the back stack but will _not_
 * survive process death.
 *
 * ```kotlin
 * @Composable
 * override fun present(): CounterState {
 *   var state by rememberRetained { mutableStateOf(CounterState(0)) }
 *
 *   return CounterState(count) { event ->
 *     when (event) {
 *       is CounterEvent.Increment -> state = state.copy(count = state.count + 1)
 *       is CounterEvent.Decrement -> state = state.copy(count = state.count - 1)
 *     }
 *   }
 * }
 * ```
 *
 * This second case will retain `count` across configuration changes, the back stack, _and_ survive
 * process death. However, it only works with primitives or implicitly Saveable (i.e. `Parcelable`
 * on Android) state types.
 *
 * ```kotlin
 * @Composable
 * override fun present(): CounterState {
 *   var count by rememberSaveable { mutableStateOf(0) }
 *
 *   return CounterState(count) { event ->
 *     when (event) {
 *       is CounterEvent.Increment -> state = count++
 *       is CounterEvent.Decrement -> state = count--
 *     }
 *   }
 * }
 * ```
 *
 * ## Layering
 *
 * There is also an overload of [rememberRetained] that takes a [Saver], which participates in both
 * the saved state registry system and retaining. Alternatively, use [rememberRetainedSaveable] for
 * one that uses [autoSaver]. These can be used to persist state across multiple layers, allowing
 * for both the caching of [rememberRetained] while also the process-death-survival of saveable.
 *
 * @param inputs A set of inputs such that, when any of them have changed, will cause the state to
 *   reset and [init] to be rerun
 * @param key An optional key to be used as a key for the saved value. If not provided we use the
 *   automatically generated by the Compose runtime which is unique for the every exact code
 *   location in the composition tree
 * @param init A factory function to create the initial value of this state
 */
@OptIn(DelicateCircuitRetainedApi::class)
@Composable
public fun <T : Any> rememberRetained(vararg inputs: Any?, key: String? = null, init: () -> T): T =
  rememberRetained(inputs = inputs, saver = neverSave(), key = key, init = init)

/**
 * A simple proxy to [rememberRetained] that uses the default [autoSaver] for [saver] and a more
 * explicit name.
 *
 * @see rememberRetained
 */
@OptIn(DelicateCircuitRetainedApi::class)
@Composable
public fun <T : Any> rememberRetainedSaveable(
  vararg inputs: Any?,
  saver: Saver<T, out Any> = autoSaver(),
  key: String? = null,
  init: () -> T,
): T = rememberRetained(inputs = inputs, saver = saver, key = key, init = init)

/**
 * Remember the value produced by [init].
 *
 * It behaves similarly to [rememberRetained] by retaining the value in memory, but with an
 * additional [saver] parameter that will opportunistically save the value to saved instance state.
 *
 * The retained value will be the source of truth for the value, except in the case where the
 * process was killed, and the value is being restored. In that situation only, the most recent
 * value recovered from the saveable state registry will be used instead of the value provided by
 * [init].
 *
 * Importantly, the most recent value persisted in saveable state may lag behind the value that is
 * saved in memory. For example, on Android, saved state will be collected when an Activity is
 * backgrounded and onStop is called. The retained value might continue to be updated while in the
 * background, but those updates will not be reflected in the saved state. If the process is killed
 * before the app has returned to the foreground, these background updates will be lost, as the
 * restored saved state will have been taken before the background updates occurred.
 *
 * Therefore, [saver] should only store and restore information that is logically safe to have an
 * out-of-date view of.
 *
 * @param inputs A set of inputs such that, when any of them have changed, will cause the state to
 *   reset and [init] to be rerun
 * @param saver The [Saver] object which defines how the state is saved and restored.
 * @param key An optional key to be used as a key for the saved value. If not provided we use the
 *   automatically generated by the Compose runtime which is unique for the every exact code
 *   location in the composition tree
 * @param init A factory function to create the initial value of this state
 */
@DelicateCircuitRetainedApi
@Composable
public fun <T : Any> rememberRetained(
  vararg inputs: Any?,
  saver: Saver<T, out Any>,
  key: String? = null,
  init: () -> T,
): T {
  val saveableStateRegistry = LocalSaveableStateRegistry.current
  val retainedStateRegistry = LocalRetainedStateRegistry.current

  val compositeKey = currentCompositeKeyHash
  // key is the one provided by the user or the one generated by the compose runtime
  val finalKey =
    if (!key.isNullOrEmpty()) {
      key
    } else {
      compositeKey.toString(MaxSupportedRadix)
    }

  @Suppress("UNCHECKED_CAST") (saver as Saver<T, Any>)

  val canRetainChecker = LocalCanRetainChecker.current ?: rememberCanRetainChecker()
  val holder =
    remember(canRetainChecker) {
      // value is restored using the retained registry first, the saveable registry second, or
      // created via [init] lambda third
      @Suppress("UNCHECKED_CAST")
      val retainedRestored =
        retainedStateRegistry.consumeValue(finalKey) as? RetainableSaveableHolder.Value<T>
      val saveableRestored =
        saveableStateRegistry?.consumeRestored(finalKey)?.let { saver.restore(it) }
      val finalValue = retainedRestored?.value ?: saveableRestored ?: init()
      val finalInputs = retainedRestored?.inputs ?: inputs
      RetainableSaveableHolder(
        retainedStateRegistry = retainedStateRegistry,
        canRetainChecker = canRetainChecker,
        saveableStateRegistry = saveableStateRegistry,
        saver = saver,
        key = finalKey,
        value = finalValue,
        inputs = finalInputs,
        hasBeenRestoredFromRetained = retainedRestored != null,
      )
    }
  val value = holder.getValueIfInputsAreEqual(inputs) ?: init()
  SideEffect {
    holder.update(retainedStateRegistry, saveableStateRegistry, saver, finalKey, value, inputs)
  }
  return value
}

/**
 * Remember the value produced by [init].
 *
 * It behaves similarly to [remember], but the stored value will survive configuration changes, such
 * as a screen rotation.
 *
 * Use this overload if you remember a mutable state to specify a [stateSaver] for the value
 * contained inside the [MutableState].
 *
 * @param inputs A set of inputs such that, when any of them have changed, will cause the state to
 *   reset and [init] to be rerun
 * @param stateSaver The [Saver] object which defines how the value inside the MutableState is saved
 *   and restored.
 * @param key An optional key to be used as a key for the saved value. If not provided we use the
 *   automatically generated by the Compose runtime which is unique for the every exact code
 *   location in the composition tree
 * @param init A factory function to create the initial value of this state
 */
@DelicateCircuitRetainedApi
@Composable
public fun <T> rememberRetained(
  vararg inputs: Any?,
  stateSaver: Saver<T, out Any>,
  key: String? = null,
  init: () -> MutableState<T>,
): MutableState<T> =
  rememberRetained(*inputs, saver = mutableStateSaver(stateSaver), key = key, init = init)

/**
 * A simple proxy to [rememberRetained] that uses the default [autoSaver] for [saver] and a more
 * explicit name.
 *
 * @see rememberRetained
 */
@DelicateCircuitRetainedApi
@Composable
public fun <T> rememberRetainedSaveable(
  vararg inputs: Any?,
  stateSaver: Saver<T, out Any>,
  key: String? = null,
  init: () -> MutableState<T>,
): MutableState<T> =
  rememberRetained(*inputs, saver = mutableStateSaver(stateSaver), key = key, init = init)

/** The maximum radix available for conversion to and from strings. */
private const val MaxSupportedRadix = 36

private val NoOpSaver = Saver<Any?, Any>({ null }, { null })

@Suppress("UNCHECKED_CAST")
private fun <Original, Saveable : Any> neverSave() = NoOpSaver as Saver<Original, Saveable>

private class RetainableSaveableHolder<T>(
  private var retainedStateRegistry: RetainedStateRegistry?,
  private var canRetainChecker: CanRetainChecker,
  private var saveableStateRegistry: SaveableStateRegistry?,
  private var saver: Saver<T, Any>,
  private var key: String,
  private var value: T,
  private var inputs: Array<out Any?>,
  private var hasBeenRestoredFromRetained: Boolean = false,
) : RetainedValueProvider, RememberObserver, SaverScope {
  private var retainedStateEntry: RetainedStateRegistry.Entry? = null
  private var saveableStateEntry: SaveableStateRegistry.Entry? = null

  /** Value provider called by the registry. */
  private val valueProvider = {
    with(saver) { save(requireNotNull(value) { "Value should be initialized" }) }
  }

  fun update(
    retainedStateRegistry: RetainedStateRegistry?,
    saveableStateRegistry: SaveableStateRegistry?,
    saver: Saver<T, Any>,
    key: String,
    value: T,
    inputs: Array<out Any?>,
  ) {
    var retainedEntryIsOutdated = false
    var saveableEntryIsOutdated = false
    if (this.retainedStateRegistry !== retainedStateRegistry) {
      this.retainedStateRegistry = retainedStateRegistry
      retainedEntryIsOutdated = true
    }
    if (this.saveableStateRegistry !== saveableStateRegistry) {
      this.saveableStateRegistry = saveableStateRegistry
      saveableEntryIsOutdated = true
    }
    if (this.key != key) {
      this.key = key
      retainedEntryIsOutdated = true
      saveableEntryIsOutdated = true
    }
    if (this.value !== value) {
      // If the value changes, clear the hasBeenRestored flag
      hasBeenRestoredFromRetained = false
    }
    this.saver = saver
    this.value = value
    this.inputs = inputs
    if (retainedStateEntry != null && retainedEntryIsOutdated) {
      retainedStateEntry?.unregister()
      retainedStateEntry = null
      registerRetained()
    }
    if (saveableStateRegistry != null && saveableEntryIsOutdated) {
      saveableStateEntry?.unregister()
      saveableStateEntry = null
      registerSaveable()
    }
  }

  private fun registerRetained() {
    val registry = retainedStateRegistry
    require(retainedStateEntry == null) { "entry($retainedStateEntry) is not null" }
    if (registry != null) {
      retainedStateEntry = registry.registerValue(key, this)
    }
  }

  private fun registerSaveable() {
    val registry = saveableStateRegistry
    require(saveableStateEntry == null) { "entry($saveableStateEntry) is not null" }
    if (registry != null) {
      registry.requireCanBeSaved(valueProvider())
      saveableStateEntry = registry.registerProvider(key, valueProvider)
    }
  }

  /** Value provider called by the registry. */
  override fun invoke(): Any =
    Value(value = requireNotNull(value) { "Value should be initialized" }, inputs = inputs)

  override fun canBeSaved(value: Any): Boolean {
    val registry = saveableStateRegistry
    return registry == null || registry.canBeSaved(value)
  }

  fun saveIfRetainable() {
    val v = value ?: return
    val reg = retainedStateRegistry ?: return

    if (!canRetainChecker.canRetain(reg)) {
      retainedStateEntry?.unregister()
      when (v) {
        // If value is a RememberObserver, we notify that it has been forgotten.
        is RememberObserver -> v.onForgotten()
        // Or if its a registry, we need to tell it to clear, which will forward the 'forgotten'
        // call onto its values
        is RetainedStateRegistry -> {
          // First we saveAll, which flattens down the value providers to our retained list
          v.saveAll()
          // Now we drop all retained values
          v.forgetUnclaimedValues()
        }
      }
    } else if (v is RetainedStateRegistry) {
      // If the value is a RetainedStateRegistry, we need to take care to retain it.
      // First we tell it to saveAll, to retain it's values. Then we need to tell the host
      // registry to retain the child registry.
      v.saveAll()
      reg.saveValue(key)
    }
  }

  override fun onRemembered() {
    registerRetained()
    registerSaveable()

    // If value is a RememberObserver, we notify that it has remembered
    if (!hasBeenRestoredFromRetained) {
      val v = value
      if (v is RememberObserver) v.onRemembered()
    }
  }

  override fun onForgotten() {
    saveIfRetainable()
    saveableStateEntry?.unregister()
  }

  override fun onAbandoned() {
    saveIfRetainable()
    saveableStateEntry?.unregister()
  }

  fun getValueIfInputsAreEqual(inputs: Array<out Any?>): T? {
    return value.takeIf { inputs.contentEquals(this.inputs) }
  }

  class Value<T>(override val value: T, val inputs: Array<out Any?>) : RetainedValueHolder<T>
}

private fun SaveableStateRegistry.requireCanBeSaved(value: Any?) {
  require(value == null || canBeSaved(value)) {
    if (value is SnapshotMutableState<*>) {
      if (
        value.policy !== neverEqualPolicy<Any?>() &&
          value.policy !== structuralEqualityPolicy<Any?>() &&
          value.policy !== referentialEqualityPolicy<Any?>()
      ) {
        "If you use a custom SnapshotMutationPolicy for your MutableState you have to" +
          " write a custom Saver"
      } else {
        "MutableState containing ${value.value} cannot be saved using the current " +
          "SaveableStateRegistry. The default implementation only supports types " +
          "which can be stored inside the Bundle. Please consider implementing a " +
          "custom Saver for this class and pass it as a stateSaver parameter to " +
          "rememberRetainedSaveable()."
      }
    } else {
      "$value cannot be saved using the current SaveableStateRegistry. The default " +
        "implementation only supports types which can be stored inside the Bundle" +
        ". Please consider implementing a custom Saver for this class and pass it" +
        " to rememberRetainedSaveable()."
    }
  }
}

@Suppress("UNCHECKED_CAST")
private fun <T> mutableStateSaver(inner: Saver<T, out Any>) =
  with(inner as Saver<T, Any>) {
    Saver<MutableState<T>, MutableState<Any?>>(
      save = { state ->
        require(state is SnapshotMutableState<T>) {
          "If you use a custom MutableState implementation you have to write a custom " +
            "Saver and pass it as a saver param to rememberRetainedSaveable()"
        }
        val saved = save(state.value)
        if (saved != null) {
          mutableStateOf(saved, state.policy as SnapshotMutationPolicy<Any?>)
        } else {
          // if the inner saver returned null we need to return null as well so the
          // user's init lambda will be used instead of restoring mutableStateOf(null)
          null
        }
      },
      restore =
        @Suppress("UNCHECKED_CAST", "ExceptionMessage") {
          require(it is SnapshotMutableState<Any?>)
          mutableStateOf(
            if (it.value != null) restore(it.value!!) else null,
            it.policy as SnapshotMutationPolicy<T?>,
          )
            as MutableState<T>
        },
    )
  }
