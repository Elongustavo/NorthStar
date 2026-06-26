package com.example.northstar.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Última notificação (não-chamada) a exibir no painel, alimentada pelo NorthstarNotificationListener.
 * Mesmo padrão do CallInfoProvider: o listener é instanciado pelo sistema, então empurra aqui e o
 * renderizador do mapa lê [current]. Não há widget nativo do painel pra isso, então vira uma faixa.
 */
object NotificationInfoProvider {
    /** Quanto tempo (ms) a faixa fica visível no painel. */
    const val DISPLAY_MS = 6_000L

    private val _current = MutableStateFlow<DashNotification?>(null)
    val current: StateFlow<DashNotification?> = _current.asStateFlow()

    fun post(n: DashNotification) { _current.value = n }
    fun clear(key: String) { if (_current.value?.key == key) _current.value = null }
}

data class DashNotification(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long = System.currentTimeMillis(),
)
