package com.ledger.android.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.koin.core.context.GlobalContext

/**
 * 从 Koin 全局容器取实例。
 *
 * 为什么不用 `koinViewModel()`：那需要额外引入 `koin-androidx-compose`；
 * 而本工程的 ViewModel 全是「构造参数即用例」的普通类，用 [KoinViewModelFactory]
 * 显式 new 出来更直观，也顺带让 ViewModel 保持可单测（无需 Koin 容器）。
 */
inline fun <reified T : Any> inject(): T = GlobalContext.get().get(T::class)

/**
 * 把 `() -> VM` 适配成 `ViewModelProvider.Factory`。
 *
 * 只在 `modelClass` 与 VM 类型不符时才会被系统拒绝，因此这里不做额外类型检查——
 * 每个调用点都紧挨着 `val vm: XxxViewModel = viewModel(factory = ...)`，类型由编译器保证。
 */
class KoinViewModelFactory<VM : ViewModel>(
    private val create: () -> VM,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
