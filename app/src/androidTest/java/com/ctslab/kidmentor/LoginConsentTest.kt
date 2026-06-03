package com.ctslab.kidmentor

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Consent gating tests for KidMentor LoginActivity (T1 in LOGIN_TEST_PLAN.md).
 * Run: ./gradlew :app:connectedDebugAndroidTest
 *      -Pandroid.testInstrumentationRunnerArguments.class=com.ctslab.kidmentor.LoginConsentTest
 *
 * NOTE: the Privacy/Terms links are now inline ClickableSpans inside tvConsent
 * (set in LoginActivity.setupConsentText); span taps aren't covered by Espresso here —
 * verify link navigation manually per the test plan (T1-6/T1-7).
 */
@RunWith(AndroidJUnit4::class)
class LoginConsentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(LoginActivity::class.java)

    /** T1-2: both buttons disabled when checkbox is unchecked (initial state). */
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

    /** T1-4: both buttons enabled after ticking the checkbox. */
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

    /** T1-5: buttons go back to disabled after unchecking. */
    @Test
    fun buttonsDisabledAfterUnchecking() {
        onView(withId(R.id.cbAgreeTerms)).perform(click()) // check
        onView(withId(R.id.cbAgreeTerms)).perform(click()) // uncheck
        onView(withId(R.id.btnSSO)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnGuest)).check(matches(not(isEnabled())))
    }
}
