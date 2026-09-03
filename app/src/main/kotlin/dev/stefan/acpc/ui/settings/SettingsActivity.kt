package dev.stefan.acpc.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.stefan.acpc.R
import dev.stefan.acpc.ui.roms.RomSetupActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("roms_setup")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), RomSetupActivity::class.java)); true
            }
            findPreference<Preference>("physical_keymap_edit")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), KeyMappingActivity::class.java).putExtra(KeyMappingActivity.EXTRA_GAMEPAD, false)); true
            }
            findPreference<Preference>("gamepad_map_edit")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), KeyMappingActivity::class.java).putExtra(KeyMappingActivity.EXTRA_GAMEPAD, true)); true
            }
        }
    }
}
