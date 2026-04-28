package app.kumacheck.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Q3: shared replacement for the `combine(...).stateIn(viewModelScope,
 * SharingStarted.WhileSubscribed(5_000), default)` boilerplate that was
 * repeated across ~10 ViewModels. Centralises the 5 s subscription
 * keep-alive value so a future tuning change is one edit.
 *
 * The 5 s window matches Compose's standard "configuration change keeps
 * the upstream alive across a rotation but not across a full backstack
 * pop" guidance — same value Now-In-Android and most AndroidX samples
 * use.
 */
internal fun <T> Flow<T>.stateInVm(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(STATE_IN_VM_TIMEOUT_MS), initial)

private const val STATE_IN_VM_TIMEOUT_MS = 5_000L
