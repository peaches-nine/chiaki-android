// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.toLiveData
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers

class MainViewModel(val database: AppDatabase, val preferences: Preferences): ViewModel()
{
	private val disposable = CompositeDisposable()

	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		it.discoveryActive
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe { preferences.discoveryEnabled = it }
			.addTo(disposable)
	}

	val displayHosts by lazy {
		Observables.combineLatest(
			database.manualHostDao().getAll().toObservable(),
			database.registeredHostDao().getAll().toObservable(),
			discoveryManager.discoveredHosts
		) { manualHosts, registeredHosts, discoveredHosts ->

			// 使用 groupBy 处理多个 serverMac 匹配的情况
			val macRegisteredHosts = registeredHosts
				.groupBy { it.serverMac } // 按 serverMac 分组
			val idRegisteredHosts = registeredHosts.associateBy { it.id }

			// 处理 DiscoveredHosts
			val discoveredDisplayHosts = discoveredHosts.flatMap { discoveredHost ->
				val mac = discoveredHost.serverMac
				val registeredHostsForMac = macRegisteredHosts[mac] ?: emptyList()

				// 如果没有匹配的 RegisteredHost，可以选择跳过，或者处理为 null 的情况
				if (registeredHostsForMac.isEmpty()) {
					// 如果没有找到对应的 RegisteredHost，创建一个包含 null 的 DiscoveredDisplayHost
					listOf(DiscoveredDisplayHost(null, discoveredHost))
				} else {
					// 为每个匹配的 RegisteredHost 创建一个 DiscoveredDisplayHost
					registeredHostsForMac.map { registeredHost ->
						DiscoveredDisplayHost(registeredHost, discoveredHost)
					}
				}
			}

			// 处理 ManualHosts
			val manualDisplayHosts = manualHosts.map { manualHost ->
				val registeredHost = manualHost.registeredHost?.let { id -> idRegisteredHosts[id] }
				ManualDisplayHost(registeredHost, manualHost)
			}

			// 合并 Discovered 和 Manual Display Hosts
			discoveredDisplayHosts + manualDisplayHosts
		}.toLiveData()
	}



//	val displayHosts by lazy {
//		Observables.combineLatest(
//			database.manualHostDao().getAll().toObservable(),
//			database.registeredHostDao().getAll().toObservable(),
//			discoveryManager.discoveredHosts)
//		{ manualHosts, registeredHosts, discoveredHosts ->
//			val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
//			val idRegisteredHosts = registeredHosts.associateBy { it.id }
//			discoveredHosts.map {
//				DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
//			} +
//					manualHosts.map {
//						ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it)
//					}
//		}
//			.toLiveData()
//	}


	val discoveryActive by lazy {
		discoveryManager.discoveryActive.toLiveData()
	}

	fun deleteManualHost(manualHost: ManualHost)
	{
		database.manualHostDao()
			.delete(manualHost)
			.onErrorComplete()
			.subscribeOn(Schedulers.io())
			.subscribe()
			.addTo(disposable)
	}

	override fun onCleared()
	{
		super.onCleared()
		disposable.dispose()
		discoveryManager.dispose()
	}
}