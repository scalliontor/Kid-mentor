package com.ctslab.ptalk_signature

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Consent gating tests for P-Talk Signature LoginActivity (T1 in LOGIN_TEST_PLAN.md).
 * Run: ./gradlew :app:connectedDebugAndroidTest
 *      -Pandroid.testInstrumentationRunnerArguments.class=com.ctslab.ptalk_signature.LoginConsentTest
 *
 * NOTE: Privacy/Terms links are inline ClickableSpans inside tvConsent
 * (LoginActivity.setupConsentText); span taps verified manually per plan (T1-6/T1-7).
 */
@RunWith(AndroidJUnit4::class)
class LoginConsentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(LoginActivity::class.java)

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

    /** T1-4: buttons enabled and error hidden after tick. */
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

    /** T1-5: buttons back to disabled after uncheck. */
    @Test
    fun buttonsDisabledAfterUncheck() {
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.cbAgreeTerms)).perform(click())
        onView(withId(R.id.btnLogin)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnGuest)).check(matches(not(isEnabled())))
    }
}
