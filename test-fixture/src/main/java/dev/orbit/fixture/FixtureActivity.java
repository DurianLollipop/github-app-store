package dev.orbit.fixture;

public class FixtureActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("Orbit 本机安装与升级测试夹具");
        label.setTextSize(28);
        setContentView(label);
    }
}
