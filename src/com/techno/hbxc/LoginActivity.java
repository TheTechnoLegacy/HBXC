package com.techno.hbxc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public class LoginActivity extends Activity implements App.UiListener {
    EditText jid, pass, pass2; Button go; TextView msg, logv, toggle, hint; boolean waiting, reg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (App.I.hasAccount()) { startActivity(new Intent(this, MainActivity.class)); finish(); return; }
        int p = Ui.dp(this, 24);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); l.setPadding(p, p, p, p); l.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()));
        l.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        TextView t = new TextView(this); t.setText("HBXC"); t.setTextSize(26); t.setTextColor(Ui.GREEN);
        t.setPadding(0, p / 2, 0, p); l.addView(t);
        jid = new EditText(this); jid.setHint("username@yax.im"); jid.setSingleLine();
        jid.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        l.addView(jid, new LinearLayout.LayoutParams(-1, -2));
        pass = new EditText(this); pass.setHint("Password"); pass.setSingleLine();
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        l.addView(pass, new LinearLayout.LayoutParams(-1, -2));
        pass2 = new EditText(this); pass2.setHint("Confirm password"); pass2.setSingleLine(); pass2.setVisibility(View.GONE);
        pass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        l.addView(pass2, new LinearLayout.LayoutParams(-1, -2));
        hint = new TextView(this); hint.setTextSize(13); hint.setTextColor(0xff777777); hint.setVisibility(View.GONE);
        hint.setText("Pick a username of at least 2 letters. There is no password recovery, so write your password down.");
        l.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        go = new Button(this); go.setText("Sign in");
        l.addView(go, new LinearLayout.LayoutParams(-1, -2));
        toggle = new TextView(this); toggle.setText("Create account"); toggle.setTextColor(Ui.GREEN); toggle.setTextSize(16);
        toggle.setPadding(0, p / 2, 0, p / 2); toggle.setGravity(Gravity.CENTER);
        toggle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setReg(!reg); } });
        l.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        msg = new TextView(this); msg.setTextColor(Color.RED); msg.setPadding(0, p / 2, 0, 0); l.addView(msg);
        logv = new TextView(this); logv.setTextSize(12); logv.setTextIsSelectable(true); logv.setTypeface(android.graphics.Typeface.MONOSPACE); logv.setTextColor(0xff666666); logv.setPadding(0, p / 2, 0, 0);
        l.addView(logv, new LinearLayout.LayoutParams(-1, -2));
        ScrollView sv = new ScrollView(this); sv.addView(l);
        setContentView(sv);
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { doLogin(); } });
    }

    void setReg(boolean r) {
        if (waiting) return;
        reg = r; msg.setText("");
        pass2.setVisibility(r ? View.VISIBLE : View.GONE); hint.setVisibility(r ? View.VISIBLE : View.GONE);
        go.setText(r ? "Create account" : "Sign in");
        toggle.setText(r ? "Already have an account? Sign in" : "Create account");
    }

    void doLogin() {
        String j = jid.getText().toString().trim().toLowerCase(), pw = pass.getText().toString();
        if (j.length() > 0 && j.indexOf('@') < 0) j = j + "@yax.im";
        if (!j.matches("[^@/\\s]+@[^@/\\s]+\\.[^@/\\s]+") || pw.length() == 0) { msg.setText("Enter your full address (user@yax.im) and password."); return; }
        if (reg) {
            if (j.substring(0, j.indexOf('@')).length() < 2) { msg.setText("The username needs at least 2 characters."); return; }
            if (pw.length() < 6) { msg.setText("Choose a password of at least 6 characters."); return; }
            if (!pw.equals(pass2.getText().toString())) { msg.setText("The passwords don't match."); return; }
        }
        msg.setText(""); go.setEnabled(false); waiting = true;
        if (reg) App.I.register(j, pw); else App.I.login(j, pw);
    }

    @Override protected void onResume() { super.onResume(); App.I.add(this); if (logv != null) logv.setText(XmppClient.logText()); }
    @Override protected void onPause() { super.onPause(); App.I.remove(this); }

    public void onChanged() { }
    public void onState(String s) {
        if (logv != null) logv.setText(XmppClient.logText());
        if (!waiting) return;
        if (s.equals("Online")) { waiting = false; startActivity(new Intent(this, MainActivity.class)); finish(); }
        else if (s.startsWith("Error")) {
            if (s.startsWith("Error: Registration")) { waiting = false; App.I.logout(); go.setEnabled(true); String t = s.substring(7); msg.setText(t.replace("Registration: ", "Couldn't create the account: ")); }
            else if (s.contains("Authentication")) { waiting = false; App.I.logout(); go.setEnabled(true); msg.setText("Wrong username or password."); }
            else msg.setText(s + " – retrying…");
        }
        if (s.equals("Online")) msg.setText("");
    }
}
