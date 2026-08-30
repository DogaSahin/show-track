package com.anarky.showtrack.core.data.di

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl
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
}
