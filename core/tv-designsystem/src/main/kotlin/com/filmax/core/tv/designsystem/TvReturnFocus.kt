package com.filmax.core.tv.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Возврат фокуса на карточку, с которой ушли на пуш-экран (детали, плеер, подборка).
 *
 * NavHost восстанавливает скролл вернувшегося экрана, но не фокус: без этого механизма фокус
 * доставался первому focusable экрана (hero, поиск), и bring-into-view утаскивал прокрутку
 * к нему — «экран теряет позицию». Экран помечает карточку в onClick через [onOpen] и вешает
 * [target] на неё же — при следующем входе в композицию фокус приезжает ровно туда, а
 * восстановленная прокрутка остаётся на месте (карточка уже видима).
 *
 * Ключ — стабильный идентификатор карточки, уникальный в пределах экрана («continue:42»,
 * «grid:17»): один тайтл может встречаться в нескольких рядах сразу.
 */
class TvReturnFocus internal constructor(
    private val savedKey: MutableState<String?>,
) {
    private val ownRequester = FocusRequester()

    /** Реквестер, через который поедет возврат: свой либо занятый фоллбеком ряда (см. [target]). */
    private var boundRequester: FocusRequester = ownRequester

    internal val requester: FocusRequester
        get() = boundRequester

    /** Вызывать в onClick карточки перед навигацией: сюда вернётся фокус. */
    fun onOpen(key: String) {
        savedKey.value = key
    }

    /**
     * FocusRequester для карточки ряда: помеченной — тот, через который вернётся фокус.
     *
     * [railFallback] — фоллбек `focusRestorer` ряда (см. TvRail). Он обязан быть привязан
     * ВСЕГДА: restorer дёргает его при каждом входе фокуса в ряд и падает с «FocusRequester is
     * not initialized», если узла нет. При этом на одном реквестере не может висеть два узла —
     * фокус уехал бы на первый из них. Отсюда правило владения:
     *
     *  - в ряду есть помеченная карточка → фоллбек забирает она;
     *  - иначе → первая карточка ряда ([isFirstInRail]).
     *
     * Так возврат приезжает прямо на помеченную карточку. Раньше фоллбек всегда сидел на первой:
     * фокус входил в ряд, restorer перехватывал вход и уводил его на первую карточку, ряд
     * отматывался в начало — и только следующим кадром фокус доезжал до нужной карточки.
     */
    fun target(
        key: String,
        railFallback: FocusRequester? = null,
        isFirstInRail: Boolean = false,
    ): FocusRequester? {
        val saved = savedKey.value
        if (saved == key) {
            boundRequester = railFallback ?: ownRequester
            return boundRequester
        }
        // Ключи карточек — «ряд:id», поэтому префикс помеченного ключа и говорит, чей это ряд.
        val markedInSameRail = saved != null && saved.railOf() == key.railOf()
        return railFallback?.takeIf { isFirstInRail && !markedInSameRail }
    }

    internal fun consume() {
        savedKey.value = null
        boundRequester = ownRequester
    }

    /** Есть ли отложенный возврат: экран не открыт заново, а вернулся с пуш-экрана. */
    fun pending(): Boolean = savedKey.value != null
}

/** Ряд, которому принадлежит ключ карточки («continue:42» → «continue»). */
private fun String.railOf(): String = substringBefore(':')

/**
 * Создаёт [TvReturnFocus] и восстанавливает фокус при возврате на экран.
 *
 * Ленивые списки компонуют карточки в фазе измерения, а прокрученный ряд добирается до своей
 * позиции не с первого кадра — поэтому реквест ретраится несколько кадров подряд, пока
 * помеченная карточка не привяжет FocusRequester. Всё это успевает раньше глобального
 * фоллбека графа — тот увидит занятый фокус и не станет вмешиваться. Если помеченной карточки
 * больше нет (список изменился), попытки молча кончаются, и фокус ставит фоллбек графа.
 */
@Composable
fun rememberTvReturnFocus(): TvReturnFocus {
    val savedKey = rememberSaveable { mutableStateOf<String?>(null) }
    val returnFocus = remember { TvReturnFocus(savedKey) }
    LaunchedEffect(Unit) {
        if (!returnFocus.pending()) return@LaunchedEffect
        repeat(RETURN_FOCUS_ATTEMPTS) {
            withFrameNanos { }
            val granted = runCatching { returnFocus.requester.requestFocus() }.isSuccess
            if (granted) {
                // Страховка на случай, когда вход фокуса в группу кто-то перехватил и увёл на
                // свой fallback: повторный реквест идёт уже изнутри группы, enter не срабатывает.
                // Для рядов это давно no-op — фоллбек ряда держит сама помеченная карточка.
                withFrameNanos { }
                runCatching { returnFocus.requester.requestFocus() }
                returnFocus.consume()
                return@LaunchedEffect
            }
        }
        returnFocus.consume()
    }
    return returnFocus
}

private const val RETURN_FOCUS_ATTEMPTS = 8
