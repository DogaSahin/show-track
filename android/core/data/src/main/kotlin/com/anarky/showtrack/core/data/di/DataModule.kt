package com.anarky.showtrack.core.data.di

import com.anarky.showtrack.core.data.auth.AuthEventSource
import com.anarky.showtrack.core.data.auth.AuthEventSourceImpl
import com.anarky.showtrack.core.data.push.DataStorePushRegistrationStore
import com.anarky.showtrack.core.data.push.PushRegistrationStore
import com.anarky.showtrack.core.data.push.PushRepository
import com.anarky.showtrack.core.data.push.PushRepositoryImpl
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.AuthRepositoryImpl
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.data.repository.MediaRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The edge that makes architecture rule 2 usable rather than merely enforced: everything upstream
 * of here binds concrete types, and this is where the graph starts handing out an interface. A
 * `:feature:*` ViewModel asks for [LibraryRepository] and never learns that Retrofit or Room were
 * involved.
 */
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
     * No `@Singleton` on the method for the third time, and the same reasoning:
     * [PushRepositoryImpl] carries the scope. It matters here because the impl holds a
     * `PushRegistrationStore`, and DataStore THROWS if two instances are constructed over the
     * same file in one process — an unscoped binding plus one direct injection of the concrete
     * type would be exactly that crash. (`PushRegistrationStore` is itself `@Singleton`, so this
     * is belt and braces rather than the only guard.)
     */
    @Binds
    abstract fun pushRepository(impl: PushRepositoryImpl): PushRepository

    /**
     * `DataStorePushRegistrationStore` carries the `@Singleton`, and here that is not a style
     * preference: DataStore THROWS if two instances are constructed over the same file in one
     * process, so a second instance is a crash rather than a duplicated cache.
     */
    @Binds
    abstract fun pushRegistrationStore(impl: DataStorePushRegistrationStore): PushRegistrationStore

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
}
