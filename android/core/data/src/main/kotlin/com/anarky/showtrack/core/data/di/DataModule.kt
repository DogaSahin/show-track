package com.anarky.showtrack.core.data.di

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
     * `@Singleton` is load-bearing, not a default: [LibraryRepositoryImpl] holds the paginator's
     * cursor and accumulated pages in memory, so an unscoped binding would hand each ViewModel a
     * repository that starts from page one and never sees what another already loaded.
     *
     * `@Binds` over `@Provides`: a @Provides factory has to be edited every time the
     * implementation gains a constructor dependency, and Dagger generates a redundant factory
     * class for it. The one thing @Provides was protecting here — constructing the impl directly
     * in `LibraryRepositoryImplTest` — is unaffected, since `@Inject` on a constructor does not
     * stop anyone calling it.
     */
    @Binds
    @Singleton
    abstract fun libraryRepository(impl: LibraryRepositoryImpl): LibraryRepository
}
