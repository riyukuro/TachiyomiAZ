package eu.kanade.tachiyomi.ui.migration

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.afollestad.materialdialogs.MaterialDialog
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.popControllerWithTag
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import exh.ui.migration.manga.design.MigrationDesignController
import exh.util.await
import kotlinx.android.synthetic.main.migration_controller.migration_recycler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrationController : NucleusController<MigrationPresenter>(),
        FlexibleAdapter.OnItemClickListener,
        SourceAdapter.OnSelectClickListener,
        SourceAdapter.OnAutoClickListener {

    private var adapter: FlexibleAdapter<IFlexible<*>>? = null

    private var title: String? = null
        set(value) {
            field = value
            setTitle()
        }

    override fun createPresenter(): MigrationPresenter {
        return MigrationPresenter()
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.migration_controller, container, false)
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        adapter = FlexibleAdapter(null, this)
        migration_recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(view.context)
        migration_recycler.adapter = adapter
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    override fun getTitle(): String? {
        return title
    }

    override fun handleBack(): Boolean {
        return if (presenter.state.selectedSource != null) {
            presenter.deselectSource()
            true
        } else {
            super.handleBack()
        }
    }

    fun render(state: ViewState) {
        if (state.selectedSource == null) {
            title = resources?.getString(R.string.label_migration)
            if (adapter !is SourceAdapter) {
                adapter = SourceAdapter(this)
                migration_recycler.adapter = adapter
            }
            adapter?.updateDataSet(state.sourcesWithManga)
        } else {
            title = state.selectedSource.toString()
            if (adapter !is MangaAdapter) {
                adapter = MangaAdapter(this)
                migration_recycler.adapter = adapter
            }
            adapter?.updateDataSet(state.mangaForSource)
        }
    }

    fun renderIsReplacingManga(state: ViewState) {
        if (state.isReplacingManga) {
            if (router.getControllerWithTag(LOADING_DIALOG_TAG) == null) {
                LoadingController().showDialog(router, LOADING_DIALOG_TAG)
            }
        } else {
            router.popControllerWithTag(LOADING_DIALOG_TAG)
        }
    }

    override fun onItemClick(view: View?, position: Int): Boolean {
        val item = adapter?.getItem(position) ?: return false

        if (item is MangaItem) {
            val controller = SearchController(item.manga)
            controller.targetController = this

            router.pushController(controller.withFadeTransaction())
        } else if (item is SourceItem) {
            presenter.setSelectedSource(item.source)
        }
        return false
    }

    override fun onSelectClick(position: Int) {
        onItemClick(null, position)
    }

    override fun onAutoClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return

        GlobalScope.launch {
            val manga = Injekt.get<DatabaseHelper>().getFavoriteMangas().asRxSingle().await(Schedulers.io())
            val sourceMangas = manga.asSequence().filter { it.source == item.source.id }.map { it.id!! }.toList()
            withContext(Dispatchers.Main) {
                router.pushController(MigrationDesignController.create(sourceMangas).withFadeTransaction())
            }
        }
    }

    fun migrateManga(prevManga: Manga, manga: Manga) {
        presenter.migrateManga(prevManga, manga, replace = true)
    }

    fun copyManga(prevManga: Manga, manga: Manga) {
        presenter.migrateManga(prevManga, manga, replace = false)
    }

    class LoadingController : DialogController() {

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog.Builder(activity!!)
                    .progress(true, 0)
                    .content(R.string.migrating)
                    .cancelable(false)
                    .build()
        }
    }

    companion object {
        const val LOADING_DIALOG_TAG = "LoadingDialog"
    }
}
