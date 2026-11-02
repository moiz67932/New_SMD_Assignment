package com.shadowdevelopers.i230529_i230723

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

/** Log in (02). Email + password sign in, Recent login card, Forgot password. */
class LoginActivity : AppCompatActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        showRecentAccount()

        Forms.passwordToggle(passwordInput)
        Forms.onKeyboardAction(passwordInput) { logIn() } // Done on the keyboard = Log in
        findViewById<View>(R.id.loginButton).setOnClickListener { logIn() }
        findViewById<View>(R.id.forgotPasswordButton).setOnClickListener { resetPassword() }
        findViewById<View>(R.id.signupButton).setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    /** The last account that signed in on this phone; tapping it pre-fills the email. */
    private fun showRecentAccount() {
        val email = Session.recentEmail(this)
        val card = findViewById<View>(R.id.recentLoginCard)
        if (email.isNullOrBlank()) {
            card.visibility = View.GONE
            return
        }
        val name = Session.recentName(this).takeUnless { it.isNullOrBlank() } ?: email
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.recentLoginName).text = name
        AvatarBinder.bind(findViewById(R.id.recentLoginAvatar), name, Session.recentPhoto(this))
        card.setOnClickListener {
            emailInput.setText(email)
            passwordInput.requestFocus()
        }
        findViewById<View>(R.id.recentLoginMore).setOnClickListener {
            Session.clearRecent(this)
            card.visibility = View.GONE
        }
    }

    private fun logIn() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        when {
            email.isBlank() -> return run { Forms.error(emailInput, "Enter your email") }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> return run { Forms.error(emailInput, "Enter a valid email, like name@example.com") }
            password.isBlank() -> return run { Forms.error(passwordInput, "Enter your password") }
        }
        Forms.hideKeyboard(this)
        val button = findViewById<View>(R.id.loginButton)
        Forms.setBusy(button, true, "Logging in…")
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            val user = auth.currentUser
            if (!task.isSuccessful || user == null) {
                Forms.setBusy(button, false)
                Toast.makeText(this, Forms.message(task.exception, "Login failed"), Toast.LENGTH_LONG).show()
                return@addOnCompleteListener
            }
            FirebaseData.users.child(user.uid).get()
                .addOnSuccessListener { snapshot ->
                    val complete = snapshot.child("profileSetupComplete").getValue(Boolean::class.java) == true
                    Session.setProfileComplete(this, user.uid, complete)
                    Session.saveRecent(this, email, snapshot.child("name").getValue(String::class.java) ?: email, snapshot.child("photo").getValue(String::class.java))
                    Session.startFresh(this, if (complete) HomeActivity::class.java else EditProfileActivity::class.java)
                }
                .addOnFailureListener {
                    // Don't stay half signed in when the account data cannot be read.
                    auth.signOut()
                    Forms.setBusy(button, false)
                    Toast.makeText(this, Forms.message(it, "Could not load your profile"), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun resetPassword() {
        val email = emailInput.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Forms.error(emailInput, "Type your account email here first")
            return
        }
        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener { Toast.makeText(this, "Password reset email sent to $email", Toast.LENGTH_LONG).show() }
            .addOnFailureListener { Toast.makeText(this, Forms.message(it, "Could not send the reset email"), Toast.LENGTH_LONG).show() }
    }
}
