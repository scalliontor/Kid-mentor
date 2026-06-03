package com.ctslab.ptalk_signature

import android.content.Intent
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automated consent gating tests for P-Talk Signature LoginActivity.
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 *           -Pandroid.testInstrumentationRunnerArguments.class=com.ctslab.ptalk_signature.LoginConsentTest
 */
@RunWith(AndroidJUnit4::class)
class LoginConsentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(LoginActivity::class.java)

    @Before
    fun setup() {
        Intents.init()
    }

    @After
    fun teardown() {
        Intents.release()
    }

    /** T1-2: Login and Guest disabled without consent. */
    @Test
    fun loginButtonDisabledWithoutConsent() {
        onView(withId(R.id.cbAgreeTerms)).check(matches(isNotChecked()))
        onView(withId(R.id.btnLogin)).check(matches(not(isEnabled())))
    }

    @Test
    fun guestButtonDisabledWithoutConsent() {
        onView(withId(R.id.cbAgreeTerms)).check(matches(isNotChecked()))
        onView(withId(R.id.btnGuest)).check(matches(not(isEnabled())))
    }

    /** T1-3: tvTermsError visible while unchecked. */
    @Test
    fun termsErrorVisibleWhileUnchecked() {
        onView(withId(R.id.tvTermsError))
            .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    /** T1-4: Buttons enabled and error hidden after tick. */
    @Test
    fun loginEnabledAfterConsent() {
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.btnLogin)).check(matches(isEnabled()))
        onView(withId(R.id.tvTermsError))
            .check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun guestEnabledAfterConsent() {
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.btnGuest)).check(matches(isEnabled()))
    }

    /** T1-5: Buttons back to disabled after uncheck. */
    @Test
    fun buttonsDisabledAfterUncheck() {
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.btnLogin)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnGuest)).check(matches(not(isEnabled())))
    }

    /** T1-6: Privacy link opens privacy page. */
    @Test
    fun privacyLinkOpensPrivacyPage() {
        Intents.intending(hasAction(Intent.ACTION_VIEW))
            .respondWith(androidx.test.espresso.intent.ActivityResult(0, null))
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.tvPrivacyPolicy)).perform(click())
        Intents.intended(allOf(
            hasAction(Intent.ACTION_VIEW),
            hasData("https://dashboard.ctslab.net/privacy")
        ))
    }

    /** T1-7: Terms link opens terms page. */
    @Test
    fun termsLinkOpensTermsPage() {
        Intents.intending(hasAction(Intent.ACTION_VIEW))
            .respondWith(androidx.test.espresso.intent.ActivityResult(0, null))
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.tvTermsConditions)).perform(click())
        Intents.intended(allOf(
            hasAction(Intent.ACTION_VIEW),
            hasData("https://dashboard.ctslab.net/terms")
        ))
    }
}
