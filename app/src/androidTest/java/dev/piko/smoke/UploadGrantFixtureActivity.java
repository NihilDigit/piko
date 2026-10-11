package dev.piko.smoke;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Grants its own provider's tree in an activity result, like the system picker. */
public final class UploadGrantFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent().setData(DocumentsContract.buildTreeDocumentUri(UploadGrantFixtureProvider.AUTHORITY, "root"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (getIntent().getBooleanExtra("writable", false)) result.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }
}
