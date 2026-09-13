package com.servora.android.ui.components

import android.content.Context
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one contextual top bar (`docs/decisions/011-android-contextual-top-bar.md`).
 *
 * These tests cover what the bar itself owns: the destination's own title, the back control's
 * presence and localized description, and the trailing actions the destination supplies. Which
 * destination supplies what is covered by `ServoraHomeNavigationTest`.
 */
@RunWith(AndroidJUnit4::class)
class ServoraTopBarTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsTheDestinationsOwnTitle() {
        render(root = true, title = string(R.string.customers_view_title))

        composeTestRule
            .onNodeWithTag(ServoraTopBarTitleTag)
            .assertTextEquals(string(R.string.customers_view_title))
    }

    @Test
    fun hidesTheBackControlOnARootDestination() {
        render(root = true, title = string(R.string.nav_customers))

        composeTestRule.onNodeWithTag(ServoraTopBarBackTag).assertDoesNotExist()
    }

    @Test
    fun describesItsBackControlForAScreenReaderAndInvokesIt() {
        var backs = 0
        render(root = false, title = string(R.string.customers_view_title), onBack = { backs += 1 })

        composeTestRule
            .onNodeWithTag(ServoraTopBarBackTag)
            .assertContentDescriptionEquals(string(R.string.nav_back))
            .performClick()

        assertEquals(1, backs)
    }

    @Test
    fun leavesTheActionsAreaEmptyWithoutAContextualAction() {
        render(root = true, title = string(R.string.nav_home))

        composeTestRule.onNodeWithText(string(R.string.customers_edit_short)).assertDoesNotExist()
    }

    @Test
    fun rendersTheDestinationsActionOnTheRight() {
        var edits = 0
        render(
            root = false,
            title = string(R.string.customers_view_title),
            actions = {
                TextButton(onClick = { edits += 1 }) {
                    Text(string(R.string.customers_edit_short))
                }
            },
        )

        composeTestRule
            .onNodeWithText(string(R.string.customers_edit_short))
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, edits)
    }

    @Test
    fun showsTheDestinationsContextLineWhenItHasOne() {
        render(
            root = false,
            title = string(R.string.property_add_title),
            subtitle = "ABC Property Management",
        )

        composeTestRule
            .onNodeWithTag(ServoraTopBarSubtitleTag)
            .assertTextEquals("ABC Property Management")
    }

    @Test
    fun drawsNoContextLineForADestinationWithoutOne() {
        render(root = true, title = string(R.string.nav_home))

        composeTestRule.onNodeWithTag(ServoraTopBarSubtitleTag).assertDoesNotExist()
    }

    private fun render(
        root: Boolean,
        title: String,
        subtitle: String? = null,
        onBack: () -> Unit = {},
        actions: @Composable RowScope.() -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                ServoraTopBar(
                    state = ServoraTopBarState(
                        title = title,
                        isRoot = root,
                        onBack = if (root) null else onBack,
                        subtitle = subtitle,
                        actions = actions,
                    ),
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
