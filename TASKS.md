# TASKS

Идеи по улучшению Filmax, найденные при разборе стороннего веб-клиента kpapp.link
(того же бэкенда kino.pub/kino.watch). Полный разбор с точными полями/эндпоинтами —
`doccs-api/API_CONTRACT.md`.

Резервные хосты API (самая ценная находка) уже реализованы: `ApiHostRepository`
(`core:domain`) + `ApiHostRepositoryImpl` (`core:network`) — персистентный выбор хоста,
health-check по `API_HOSTS` при сбое соединения (`ConnectionFailures` в `safeRequest`),
и пункт «Сервер API» в `TvProfileScreen`. Здесь — остальные находки.

## 1. Мелкие эндпоинты каталога для экрана деталей

Все — независимые GET-запросы, не требуют смены архитектуры (новые методы в `CatalogApi`
+ `CatalogRepositoryImpl`, см. `data/catalog`).

- [x] **`/items/similar?id={id}&perpage={n}` → `{items: [...]}`** — уже реализовано:
      `CatalogApi.getSimilarItems` → `CatalogRepository`/`CatalogRepositoryImpl` →
      `DetailsScreenModel.onFetchData` → рейл «Похожее» в `TvDetailsScreen.similarRail`.
- [x] Добавить отображение фото актера. его можно получить по этому адресу
      https://m.staticpop.net/actors/912e24a969f38fb164df06e41f0822e6.jpg — реализовано:
      `resolveCast`/`actorPhotoUrl` (`feature/details/common`) угадывает `md5(имя).jpg` на CDN
      kino.watch как фолбэк, когда у TMDB фото нет; `TvActorCard` рендерит его через Coil
      напрямую и откатывается на инициалы при 404 (часть ссылок не существует).

## 2. Недостающие поля в `ItemDto`

`data/catalog/src/commonMain/kotlin/com/filmax/data/catalog/remote/dto/ItemDto.kt` — дешёвая
добавка полей, если хотим показывать это на карточке/экране деталей:

- [x] `views` (int) — количество просмотров, форматировать как `toLocaleString()`. Показано в
      `RatingsRow` на TV-экране деталей (`feature/details/tv/TvDetailsScreen.kt`), третьей пилюлей
      рядом с рейтингами КиноПоиска/IMDb, скрыто при `views == 0`.
- [x] `advert` (bool/флаг) — показывает баннер «есть реклама в видео». Показать на универсальной карточке постера.
      Маленький бейдж «Реклама» в углу `TopStart` карточки `TvPosterCard`
      (`core/tv-designsystem/TvCards.kt`) — виден на Главной, в Каталоге, Библиотеке, Подборках и
      в ряде «Похожее» на экране деталей.

## 3. UX-паттерны плеера

- [x] **Событийный, а не интервальный `marktime`.** Было: `SaveProgress` уходил из
      1-секундного тика прогресс-бара (`TvPlayerScreen.PlayerEffects`). Стало: тик по-прежнему
      двигает UI прогресс-бара и автопереход, но `SaveProgress` теперь диспатчится только на
      события `Player.Listener`:
      - `onIsPlayingChanged(false)` — пауза (и буферизация/ошибка — тоже гасят `isPlaying`, это
        безвредно благодаря троттлингу в `saveProgress`);
      - `onPositionDiscontinuity(..., reason)` с `reason == Player.DISCONTINUITY_REASON_SEEK` —
        перемотка пультом/скрабом (другие причины разрыва — авто-переход между сегментами и
        т.п. — отфильтрованы как шум);
      - `onPlaybackStateChanged(STATE_ENDED)` — конец видео (отдельного пути «отметить
        просмотренным» в `feature/player` нет, `toggleWatched` живёт только в `feature/details`,
        конфликта нет);
      - `onDispose` того же `DisposableEffect(player)` — уход с экрана плеера. Это защита от
        регрессии: без неё выход «Назад» во время активного воспроизведения (без предварительной
        паузы) не сохранял бы позицию вовсе, хотя старый таймер такой случай покрывал (с точностью
        до ~1 c). Дедупликация по `lastSentSeconds`/`PROGRESS_STEP_SECONDS` в `saveProgress` делает
        этот вызов безвредным, если позиция уже сохранена только что.
      Троттлинг самого `saveProgress` (`PlayerScreenModel.kt`, `lastSentSeconds`,
      `PROGRESS_STEP_SECONDS`, `MIN_SECONDS_BEFORE_FIRST_SAVE`) не тронут — он уже реализует
      «только если секунда отличается от последней сохранённой» независимо от того, кто и когда
      его вызывает.
      В `PlayerScreenModel.onCleared()` аналогичный вызов сознательно НЕ добавлен: разобрано
      байткодом `androidx.lifecycle.viewmodel.internal.ViewModelImpl` — `clear()` закрывает
      `viewModelScope` (JOB_KEY-closeable) ДО вызова `onCleared()` самого `ViewModel`, так что
      `screenModelScope.launch{}` внутри `saveProgress()` там запустился бы на уже отменённом job
      и молча не выполнил бы тело — рабочим выглядящий, но мёртвый код. Эффект `onDispose` в
      Compose успевает раньше (композиция плеера покидается до того, как navigation-compose
      уничтожит `NavBackStackEntry` и очистит его `ViewModelStore`), поэтому именно там и только
      там — единственный надёжный «сейф-нет» на выход с экрана.
