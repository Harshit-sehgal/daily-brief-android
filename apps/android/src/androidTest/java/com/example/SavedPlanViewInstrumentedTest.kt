package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.PlanSurface
import com.example.data.model.SavedView
import com.example.data.prefs.SettingKeys
import com.example.data.repository.PlanRepository
import com.example.data.repository.SavedPlanViewState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedPlanViewInstrumentedTest {
  @Test
  fun namedGanttViewPersistsAppliesAndMalformedRowsFailClosed() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val suffix = System.nanoTime().toString()
    val saved =
      repository.saveSavedView(
        boardId = board.id,
        name = "Quarter $suffix",
        state = SavedPlanViewState(surface = PlanSurface.GANTT, rangeDays = 90),
      )
    val malformed =
      SavedView(
        id = "bad-view-$suffix",
        boardId = board.id,
        name = "Malformed $suffix",
        nameKey = "malformed-$suffix",
        surface = PlanSurface.GANTT,
        filtersJson = "{bad",
        rangeDays = 90,
        rank = saved.view.rank + 1,
        createdAt = 1,
        updatedAt = 1,
      )
    database.planDao().insertSavedView(malformed)
    try {
      val visible = repository.observeSavedViews(board.id).first()
      assertTrue(visible.any { it.view.id == saved.view.id })
      assertFalse(visible.any { it.view.id == malformed.id })

      repository.activateSavedView(saved.view.id, board.id)
      assertEquals(
        saved.view.id,
        database.settingDao().getSettingSync(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID)?.value,
      )
      assertEquals("Gantt", database.settingDao().getSettingSync(SettingKeys.PLAN_VIEW)?.value)
      assertEquals("90", database.settingDao().getSettingSync(SettingKeys.GANTT_RANGE_DAYS)?.value)

      val recreated = PlanRepository(context)
      val afterRecreation = recreated.observeSavedViews(board.id).first()
      assertEquals(90, afterRecreation.single { it.view.id == saved.view.id }.state.rangeDays)

      expectIllegalArgument {
        repository.saveSavedView(
          boardId = board.id,
          name = "Ｑｕａｒｔｅｒ $suffix",
          state = SavedPlanViewState(surface = PlanSurface.GANTT, rangeDays = 30),
        )
      }

      assertTrue(repository.deleteSavedView(saved.view.id, board.id))
      assertEquals(
        "",
        database.settingDao().getSettingSync(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID)?.value,
      )
      assertNull(database.planDao().getSavedView(saved.view.id))
    } finally {
      database.planDao().getSavedView(saved.view.id)?.let { database.planDao().deleteSavedView(it) }
      database.planDao().getSavedView(malformed.id)?.let { database.planDao().deleteSavedView(it) }
    }
  }

  private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
    try {
      block()
      fail("Expected an IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
      // Expected fail-closed validation.
    }
  }
}
