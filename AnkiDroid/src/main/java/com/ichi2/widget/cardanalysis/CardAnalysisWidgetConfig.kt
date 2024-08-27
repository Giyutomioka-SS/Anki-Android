/*
 *  Copyright (c) 2024 Anoop <xenonnn4w@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.widget.cardanalysis

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.DeckUtils
import com.ichi2.anki.R
import com.ichi2.anki.dialogs.DeckSelectionDialog
import com.ichi2.anki.dialogs.DeckSelectionDialog.DeckSelectionListener
import com.ichi2.anki.dialogs.DeckSelectionDialog.SelectableDeck
import com.ichi2.anki.dialogs.DiscardChangesDialog
import com.ichi2.anki.showThemedToast
import com.ichi2.anki.snackbar.BaseSnackbarBuilderProvider
import com.ichi2.anki.snackbar.SnackbarBuilder
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.widget.WidgetConfigScreenAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class CardAnalysisWidgetConfig : AnkiActivity(), DeckSelectionListener, BaseSnackbarBuilderProvider {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    lateinit var deckAdapter: WidgetConfigScreenAdapter
    private lateinit var cardAnalysisWidgetPreferences: CardAnalysisWidgetPreferences

    /**
     * Maximum number of decks allowed in the widget.
     */
    private val MAX_DECKS_ALLOWED = 1
    private var hasUnsavedChanges = false
    private var isAdapterObserverRegistered = false
    private lateinit var onBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }

        super.onCreate(savedInstanceState)

        if (!ensureStoragePermissions()) {
            return
        }

        setContentView(R.layout.widget_deck_picker_config)

        cardAnalysisWidgetPreferences = CardAnalysisWidgetPreferences(this)

        appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Timber.v("Invalid App Widget ID")
            finish()
            return
        }

        // Check if the collection is empty before proceeding and if the collection is empty, show a toast instead of the configuration view.
        lifecycleScope.launch {
            if (isCollectionEmpty()) {
                showThemedToast(
                    this@CardAnalysisWidgetConfig,
                    R.string.app_not_initialized_new,
                    false
                )
                finish()
                return@launch
            }

            initializeUIComponents()
        }
    }

    fun showSnackbar(message: CharSequence) {
        showSnackbar(
            message,
            Snackbar.LENGTH_LONG
        )
    }

    fun showSnackbar(messageResId: Int) {
        showSnackbar(getString(messageResId))
    }

    fun initializeUIComponents() {
        deckAdapter = WidgetConfigScreenAdapter { deck, position ->
            deckAdapter.removeDeck(deck.deckId)
            showSnackbar(R.string.deck_removed_from_widget)
            updateViewVisibility()
            updateFabVisibility()
            updateDoneButtonVisibility()
            hasUnsavedChanges = true
            setUnsavedChanges(true)
        }

        findViewById<RecyclerView>(R.id.recyclerViewSelectedDecks).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@CardAnalysisWidgetConfig.deckAdapter
        }

        setupDoneButton()

        findViewById<FloatingActionButton>(R.id.fabWidgetDeckPicker).setOnClickListener {
            showDeckSelectionDialog()
        }

        updateViewWithSavedPreferences()

        // Update the visibility of the "no decks" placeholder and the widget configuration container
        updateViewVisibility()

        registerReceiver(widgetRemovedReceiver, IntentFilter(AppWidgetManager.ACTION_APPWIDGET_DELETED))

        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges) {
                    showDiscardChangesDialog()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // Register the AdapterDataObserver if not already registered
        if (!isAdapterObserverRegistered) {
            deckAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    updateDoneButtonVisibility() // Update visibility when data changes
                }
            })
            isAdapterObserverRegistered = true
        }
    }

    private fun showDiscardChangesDialog() {
        DiscardChangesDialog.showDialog(
            context = this@CardAnalysisWidgetConfig,
            positiveMethod = {
                // Discard changes and finish the activity
                hasUnsavedChanges = false
                finish()
            }
        )
    }

    private fun updateCallbackState() {
        onBackPressedCallback.isEnabled = hasUnsavedChanges
    }

    // Call this method when there are unsaved changes
    private fun setUnsavedChanges(unsaved: Boolean) {
        hasUnsavedChanges = unsaved
        updateCallbackState()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiverSilently(widgetRemovedReceiver)
    }

    override val baseSnackbarBuilder: SnackbarBuilder = {
        anchorView = findViewById<FloatingActionButton>(R.id.fabWidgetDeckPicker)
    }

    private fun setupDoneButton() {
        val doneButton = findViewById<Button>(R.id.submit_button)
        val saveText = getString(R.string.save).uppercase()

        // Set the button text and click listener only once during initialization
        doneButton.text = saveText
        doneButton.setOnClickListener {
            saveSelectedDecksToPreferencesCardAnalysisWidget()
            hasUnsavedChanges = false
            setUnsavedChanges(false)

            val selectedDeckIds = cardAnalysisWidgetPreferences.getSelectedDeckIdFromPreferences(appWidgetId)

            val appWidgetManager = AppWidgetManager.getInstance(this)
            CardAnalysisWidget.updateWidget(this, appWidgetManager, appWidgetId, selectedDeckIds)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)

            sendBroadcast(Intent(this, CardAnalysisWidget::class.java))

            finish()
        }

        // Initially set the visibility based on the number of selected decks
        updateDoneButtonVisibility()
    }

    private fun updateDoneButtonVisibility() {
        val doneButton = findViewById<Button>(R.id.submit_button)
        doneButton.isVisible = deckAdapter.itemCount != 0
    }

    /** Updates the visibility of the FloatingActionButton based on the number of selected decks */
    private fun updateFabVisibility() {
        lifecycleScope.launch {
            // Directly check if there's exactly one deck selected
            val selectedDeckCount = deckAdapter.itemCount

            // Find the FloatingActionButton by its ID
            val fab = findViewById<FloatingActionButton>(R.id.fabWidgetDeckPicker)

            // Make the FAB visible only if no deck is selected (allow adding one deck)
            fab.isVisible = selectedDeckCount == 0
        }
    }

    private suspend fun isDefaultDeckEmpty(): Boolean {
        return DeckUtils.isDefaultDeckEmpty()
    }

    /** Updates the view according to the saved preference for appWidgetId.*/
    fun updateViewWithSavedPreferences() {
        val selectedDeckIds = cardAnalysisWidgetPreferences.getSelectedDeckIdFromPreferences(appWidgetId)
        if (selectedDeckIds.isNotEmpty()) {
            lifecycleScope.launch {
                val decks = fetchDecks()
                val selectedDecks = decks.filter { it.deckId in selectedDeckIds }
                selectedDecks.forEach { deckAdapter.addDeck(it) }
                updateViewVisibility()
                updateFabVisibility()
                setupDoneButton()
            }
        }
    }

    /** Asynchronously displays the list of deck in the selection dialog. */
    private fun showDeckSelectionDialog() {
        lifecycleScope.launch {
            val decks = fetchDecks()
            displayDeckSelectionDialog(decks)
        }
    }

    /** Returns the list of standard deck. */
    private suspend fun fetchDecks(): List<SelectableDeck> {
        return withContext(Dispatchers.IO) {
            SelectableDeck.fromCollection(includeFiltered = false)
        }
    }

    /** Displays the deck selection dialog with the provided list of decks. */
    private fun displayDeckSelectionDialog(decks: List<SelectableDeck>) {
        val dialog = DeckSelectionDialog.newInstance(
            title = getString(R.string.select_deck_title),
            summaryMessage = null,
            keepRestoreDefaultButton = false,
            decks = decks
        )
        dialog.show(supportFragmentManager, "DeckSelectionDialog")
    }

    /** Called when a deck is selected from the deck selection dialog. */
    override fun onDeckSelected(deck: SelectableDeck?) {
        if (deck == null) {
            return
        }

        // Check if the deck is being added to a fully occupied selection
        if (deckAdapter.itemCount >= MAX_DECKS_ALLOWED) {
            showSnackbar(R.string.deck_limit_one)
        } else {
            // Add the deck and update views
            deckAdapter.addDeck(deck)
            updateViewVisibility()
            updateFabVisibility()
            setupDoneButton()
            hasUnsavedChanges = true
            setUnsavedChanges(true)

            if (deckAdapter.itemCount == MAX_DECKS_ALLOWED) {
                showSnackbar(R.string.deck_limit_one) // Show Snackbar if the limit is now reached
            }
        }
    }

    /** Updates the visibility of the "no decks" placeholder and the widget configuration container */
    fun updateViewVisibility() {
        val noDecksPlaceholder = findViewById<View>(R.id.no_decks_placeholder)
        val widgetConfigContainer = findViewById<View>(R.id.widgetConfigContainer)

        noDecksPlaceholder.isVisible = deckAdapter.itemCount == 0
        widgetConfigContainer.isVisible = deckAdapter.itemCount > 0
    }

    fun saveSelectedDecksToPreferencesCardAnalysisWidget() {
        val selectedDecks = deckAdapter.deckIds.map { it }
        cardAnalysisWidgetPreferences.saveSelectedDeck(appWidgetId, selectedDecks.map { it.toString() })

        val updateIntent = Intent(this, CardAnalysisWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))

            putExtra("card_analysis_widget_selected_deck_ids", selectedDecks.toList().toLongArray())
        }

        sendBroadcast(updateIntent)
    }

    /** BroadcastReceiver to handle widget removal. */
    private val widgetRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AppWidgetManager.ACTION_APPWIDGET_DELETED) {
                return
            }

            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                return
            }

            context?.let { cardAnalysisWidgetPreferences.deleteDeckData(appWidgetId) }
        }
    }

    private suspend fun isCollectionEmpty(): Boolean {
        val tree = withCol { sched.deckDueTree() }
        if (tree.children.size == 1 && tree.children[0].did == 1L) {
            return isDefaultDeckEmpty()
        }
        return false
    }
}

fun ContextWrapper.unregisterReceiverSilently(receiver: BroadcastReceiver) {
    try {
        unregisterReceiver(receiver)
    } catch (e: IllegalArgumentException) {
        Timber.d(e, "unregisterReceiverSilently")
    }
}