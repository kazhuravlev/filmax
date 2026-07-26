package com.filmax.core.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Что именно фича попросила у графа. */
sealed interface NavCommand {

    /** Открыть экран. Для [Destination.Tab] граф применит семантику вкладки, а не push. */
    data class Open(val destination: Destination) : NavCommand

    /** Заменить текущий экран: следующая серия в плеере, главная после онбординга. */
    data class Replace(val destination: Destination) : NavCommand

    /** Сделать экран единственным корнем — вход в аккаунт и выход из него. */
    data class Root(val destination: Destination) : NavCommand

    /** Назад. */
    data object Back : NavCommand
}

/**
 * Навигация с точки зрения фичи: она называет адрес и не знает ни про NavController, ни про то,
 * в каком графе живёт. Команды исполняет `:app`, подписавшись на [commands].
 *
 * Заменяет колбэки, которые раньше граф раздавал каждому экрану (`onOpenItem`, `onPlay`, `onBack`,
 * …): экран был не самостоятельным, а набором дырок, которые обязан заполнить `:app`.
 */
interface Navigator {

    /** Поток команд для графа. Единственный потребитель — активный NavHost. */
    val commands: Flow<NavCommand>

    fun open(destination: Destination)

    fun replace(destination: Destination)

    fun root(destination: Destination)

    fun back()
}

/**
 * Реализация поверх [Channel]: команда не теряется, если граф ещё не подписался (первый кадр,
 * пересоздание активити), и не проигрывается повторно при новой подписке — в отличие от
 * `SharedFlow(replay = 1)`, который на возврате в композицию увёл бы экран второй раз.
 */
class AppNavigator : Navigator {

    private val queue = Channel<NavCommand>(Channel.BUFFERED)

    override val commands: Flow<NavCommand> = queue.receiveAsFlow()

    override fun open(destination: Destination) = send(NavCommand.Open(destination))

    override fun replace(destination: Destination) = send(NavCommand.Replace(destination))

    override fun root(destination: Destination) = send(NavCommand.Root(destination))

    override fun back() = send(NavCommand.Back)

    // trySend, а не send: навигацию просят из обработчика тапа — синхронно и без suspend.
    // Буфер команд не переполняется: их единицы, и потребитель разбирает очередь сразу.
    private fun send(command: NavCommand) {
        queue.trySend(command)
    }
}
