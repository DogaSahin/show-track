package com.anarky.showtrack.core.data.di

import com.anarky.showtrack.core.data.alerts.AlertSettingsStore
import com.anarky.showtrack.core.data.alerts.DataStoreAlertSettingsStore
import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.data.auth.AuthEventSourceImpl
import com.anarky.showtrack.core.data.group.ActiveGroupStore
import com.anarky.showtrack.core.data.group.DataStoreActiveGroupStore
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.AuthRepositoryImpl
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.data.repository.GroupRepositoryImpl
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.data.repository.MediaRepositoryImpl
import com.anarky.showtrack.core.data.repository.RecommendationRepository
import com.anarky.showtrack.core.data.repository.RecommendationRepositoryImpl
import com.anarky.showtrack.core.data.search.DataStoreRecentSearchStore
import com.anarky.showtrack.core.data.search.RecentSearchStore
import com.anarky.showtrack.core.data.session.UserData
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * The edge that makes architecture rule 2 usable rather than merely enforced: everything upstream
 * of here binds concrete types, and this is where the graph starts handing out an interface. A
 * `:feature:*` ViewModel asks for [LibraryRepository] and never learns that Retrofit or Room were
 * involved.
 *
 * One `@Binds` per binding is the whole content of a Hilt module, hence the function count:
 * splitting it would only scatter that edge.
 */
@Suppress("TooManyFunctions")
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    /**
     * Note where the scope is NOT: there is no `@Singleton` on this method. It sits on
     * [LibraryRepositoryImpl] itself, and the difference is real rather than stylistic.
     *
     * `@Binds @Singleton` scopes only the binding it declares — the INTERFACE. Dagger then
     * generates an unscoped provider for the implementation (verified in the generated
     * component: `libraryRepositoryImplProvider` with no `DoubleCheck` around it), so anyone
     * injecting `LibraryRepositoryImpl` concretely gets a second instance with its own paginator,
     * its own cursor and its own accumulated pages. Scoping the class instead makes the single
     * instance a property of the type rather than of the route taken to it.
     *
     * That matters because the state is not incidental: the paginator's cursor and pages live in
     * memory on the repository, so a second instance restarts pagination from page one and never
     * sees what the first already loaded.
     *
     * `@Binds` over `@Provides`: a @Provides factory has to be edited every time the
     * implementation gains a constructor dependency, and Dagger generates a redundant factory
     * class for it. The one thing @Provides was protecting here — constructing the impl directly
     * in `LibraryRepositoryImplTest` — is unaffected, since `@Inject` on a constructor does not
     * stop anyone calling it.
     */
    @Binds
    abstract fun libraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    /**
     * No `@Singleton` on the method, same reasoning as above: the scope sits on
     * [AuthEventSourceImpl]. It is a pass-through with no state of its own, but `AuthEventBus`
     * is `@Singleton` and an unscoped wrapper around a scoped singleton is one allocation per
     * injection point for no benefit.
     */
    @Binds
    abstract fun authEventSource(impl: AuthEventSourceImpl): AuthEventSource

    /**
     * No `@Singleton` on the method, same reasoning as the others above: the scope sits on
     * [AuthRepositoryImpl].
     */
    @Binds
    abstract fun authRepository(impl: AuthRepositoryImpl): AuthRepository

    /**
     * No `@Singleton` on the method, same reasoning as the others above: the scope sits on
     * [MediaRepositoryImpl], which is where the search paginator's in-memory state actually
     * lives.
     */
    @Binds
    abstract fun mediaRepository(impl: MediaRepositoryImpl): MediaRepository

    /**
     * No `@Singleton` on the method, same reasoning as the others above: the scope sits on
     * [RecommendationRepositoryImpl], which is where the recommendations paginator's in-memory
     * state actually lives.
     */
    @Binds
    abstract fun recommendationRepository(impl: RecommendationRepositoryImpl): RecommendationRepository

    /**
     * No `@Singleton` on the method, same reasoning as the others above: [GroupRepositoryImpl]
     * itself carries `@Singleton`.
     */
    @Binds
    abstract fun groupRepository(impl: GroupRepositoryImpl): GroupRepository

    /**
     * `DataStoreActiveGroupStore` carries the `@Singleton`, same reasoning as
     * the other DataStore-backed stores: DataStore throws if two instances are constructed over the
     * same file in one process.
     */
    @Binds
    abstract fun activeGroupStore(impl: DataStoreActiveGroupStore): ActiveGroupStore

    /** `DataStoreRecentSearchStore` carries the `@Singleton`, for the same DataStore reason. */
    @Binds
    abstract fun recentSearchStore(impl: DataStoreRecentSearchStore): RecentSearchStore

    /** `DataStoreAlertSettingsStore` carries the `@Singleton`, for the same DataStore reason. */
    @Binds
    abstract fun alertSettingsStore(impl: DataStoreAlertSettingsStore): AlertSettingsStore

    // Everything that holds one account's data, cleared together by UserDataCleaner at sign-in,
    // sign-out and session expiry. Each binds the class itself, which carries the @Singleton, so
    // the set holds the very instances the screens use.
    @Binds
    @IntoSet
    abstract fun libraryUserData(impl: LibraryRepositoryImpl): UserData

    @Binds
    @IntoSet
    abstract fun searchUserData(impl: MediaRepositoryImpl): UserData

    @Binds
    @IntoSet
    abstract fun recommendationUserData(impl: RecommendationRepositoryImpl): UserData

    @Binds
    @IntoSet
    abstract fun recentSearchUserData(impl: DataStoreRecentSearchStore): UserData

    @Binds
    @IntoSet
    abstract fun activeGroupUserData(impl: DataStoreActiveGroupStore): UserData
}
