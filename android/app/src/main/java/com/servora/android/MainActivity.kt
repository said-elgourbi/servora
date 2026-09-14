package com.servora.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.servora.android.data.preferences.AppLanguage
import com.servora.android.data.preferences.AppTheme
import com.servora.android.data.preferences.AppearancePreferences
import com.servora.android.ui.appearance.AppAppearance
import com.servora.android.ui.appearance.LocalAppAppearance
import com.servora.android.ui.auth.AuthFlowScreen
import com.servora.android.ui.auth.SessionViewModel
import com.servora.android.ui.customers.AddCustomerViewModel
import com.servora.android.ui.customers.AddPropertyViewModel
import com.servora.android.ui.customers.CustomersViewModel
import com.servora.android.ui.customers.EditCustomerViewModel
import com.servora.android.ui.customers.EditPropertyViewModel
import com.servora.android.ui.customers.PropertyDetailViewModel
import com.servora.android.ui.home.ManagerHomeViewModel
import com.servora.android.ui.jobs.JobDetailsViewModel
import com.servora.android.ui.passwordreset.PasswordResetViewModel
import com.servora.android.ui.signin.SignInViewModel
import com.servora.android.ui.sms.SmsSignInViewModel
import com.servora.android.ui.theme.ServoraTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single activity hosting the Compose UI.
 *
 * It is an [AppCompatActivity] because the app-wide appearance the sign-in controls change — the
 * application locale and the light/dark night mode — is applied by `AppCompatDelegate`
 * (`docs/decisions/007-android-appearance-controls.md`).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var appearancePreferences: AppearancePreferences

    // One ViewModel per destination, created by Hilt. They are created lazily on first use, so the
    // flow only builds the state it is actually showing. The session ViewModel is the exception: it
    // runs the startup restoration that decides whether the app opens signed in.
    private val sessionViewModel: SessionViewModel by viewModels()
    private val signInViewModel: SignInViewModel by viewModels()
    private val passwordResetViewModel: PasswordResetViewModel by viewModels()
    private val smsSignInViewModel: SmsSignInViewModel by viewModels()
    private val customersViewModel: CustomersViewModel by viewModels()
    private val addCustomerViewModel: AddCustomerViewModel by viewModels()
    private val editCustomerViewModel: EditCustomerViewModel by viewModels()
    private val addPropertyViewModel: AddPropertyViewModel by viewModels()
    private val propertyDetailViewModel: PropertyDetailViewModel by viewModels()
    private val editPropertyViewModel: EditPropertyViewModel by viewModels()
    private val managerHomeViewModel: ManagerHomeViewModel by viewModels()
    private val jobDetailsViewModel: JobDetailsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ServoraTheme {
                CompositionLocalProvider(LocalAppAppearance provides appAppearance()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        AuthFlowScreen(
                            sessionViewModel = sessionViewModel,
                            signInViewModel = signInViewModel,
                            passwordResetViewModel = passwordResetViewModel,
                            smsSignInViewModel = smsSignInViewModel,
                            customersViewModel = customersViewModel,
                            addCustomerViewModel = addCustomerViewModel,
                            editCustomerViewModel = editCustomerViewModel,
                            addPropertyViewModel = addPropertyViewModel,
                            propertyDetailViewModel = propertyDetailViewModel,
                            editPropertyViewModel = editPropertyViewModel,
                            managerHomeViewModel = managerHomeViewModel,
                            jobDetailsViewModel = jobDetailsViewModel,
                            // Ending a session must also drop the session-scoped UI state: the
                            // customer list belongs to the session that read it (`BR-001`).
                            onSignOut = {
                                customersViewModel.reset()
                                // The New Customer form may hold a half-typed customer that belongs
                                // to the ending session, so it is released with it (`BR-001`).
                                addCustomerViewModel.reset()
                                // The Edit Customer form holds a customer read with the ending
                                // session, so it is released with it (`BR-001`).
                                editCustomerViewModel.reset()
                                // The Property screens hold business data read with the ending
                                // session, so it is released with it (`BR-001`).
                                addPropertyViewModel.reset()
                                editPropertyViewModel.reset()
                                propertyDetailViewModel.reset()
                                // The manager home holds the operation the ending session read, so
                                // it is released with it (`BR-001`).
                                managerHomeViewModel.reset()
                                // The Job Details screen holds a Job read with the ending session,
                                // so it is released with it (`BR-001`).
                                jobDetailsViewModel.reset()
                                sessionViewModel.signOut()
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Reads the appearance the app is applying from the platform state — which already carries
     * the stored choices — and reports changes back to the code that stores them.
     *
     * Deriving rather than duplicating means the controls stay correct when the change comes from
     * outside the app, such as the system per-app language settings.
     */
    @Composable
    private fun appAppearance(): AppAppearance {
        val locales = LocalConfiguration.current.locales
        val languageTag = if (locales.isEmpty) null else locales[0].language
        return AppAppearance(
            language = AppLanguage.fromLanguageTag(languageTag),
            theme = if (isSystemInDarkTheme()) AppTheme.DARK else AppTheme.LIGHT,
            onLanguageChange = appearancePreferences::storeLanguage,
            onThemeChange = appearancePreferences::storeTheme,
        )
    }
}
