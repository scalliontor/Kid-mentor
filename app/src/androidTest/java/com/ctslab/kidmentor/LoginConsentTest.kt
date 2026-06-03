package com.ctslab.kidmentor

import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
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
 * Automated consent gating tests for KidMentor LoginActivity.
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 *           -Pandroid.testInstrumentationRunnerArguments.class=com.ctslab.kidmentor.LoginConsentTest
 *
 * These tests verify T1 in LOGIN_TEST_PLAN.md.
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

    /** T1-2: Both buttons disabled when checkbox is unchecked (initial state). */
    @Test
    fun ssoButtonDisabledWithoutConsent() {
        onView(withId(R.id.cbAgreeTerms)).check(matches(isNotChecked()))
        onView(withId(R.id.btnSSO)).check(matches(not(isEnabled())))
    }

    @Test
    fun guestButtonDisabledWithoutConsent() {
        onView(withId(R.id.cbAgreeTerms)).check(matches(isNotChecked()))
        onView(withId(R.id.btnGuest)).check(matches(not(isEnabled())))
    }

    /** T1-4: Both buttons enabled after ticking the checkbox. */
    @Test
    fun ssoButtonEnabledAfterConsent() {
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.cbAgreeTerms)).check(matches(isChecked()))
        onView(withId(R.id.btnSSO)).check(matches(isEnabled()))
    }

    @Test
    fun guestButtonEnabledAfterConsent() {
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.btnGuest)).check(matches(isEnabled()))
    }

    /** T1-5: Buttons go back to disabled after unchecking. */
    @Test
    fun buttonsDisabledAfterUnchecking() {
        onView(withId(R.id.cbAgreeTerms)).perform(click()) // check
        onView(withId(R.id.cbAgreeTerms)).perform(click()) // uncheck
        onView(withId(R.id.btnSSO)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnGuest)).check(matches(not(isEnabled())))
    }

    /** T1-6: Privacy link fires ACTION_VIEW intent to the privacy URL. */
    @Test
    fun privacyLinkOpensPrivacyPage() {
        Intents.intending(hasAction(Intent.ACTION_VIEW))
            .respondWith(androidx.test.espresso.intent.ActivityResult(0, null))
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.tvPrivacyLink)).perform(click())
        Intents.intended(allOf(
            hasAction(Intent.ACTION_VIEW),
            hasData("https://dashboard.ctslab.net/privacy")
        ))
    }

    /** T1-7: Terms link fires ACTION_VIEW intent to the terms URL. */
    @Test
    fun termsLinkOpensTermsPage() {
        Intents.intending(hasAction(Intent.ACTION_VIEW))
            .respondWith(androidx.test.espresso.intent.ActivityResult(0, null))
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.tvTermsLink)).perform(click())
        Intents.intended(allOf(
            hasAction(Intent.ACTION_VIEW),
            hasData("https://dashboard.ctslab.net/terms")
        ))
    }
}
