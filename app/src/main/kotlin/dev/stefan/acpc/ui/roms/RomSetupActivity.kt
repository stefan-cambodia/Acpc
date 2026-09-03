package dev.stefan.acpc.ui.roms

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import dev.stefan.acpc.ui.common.ToolbarActivity
import dev.stefan.acpc.R
import dev.stefan.acpc.core.machine.CpcModel
import dev.stefan.acpc.databinding.ActivityRomSetupBinding
import dev.stefan.acpc.storage.RomStore

/** Lets the user import the Amstrad ROM files (never bundled with the app). */
class RomSetupActivity : ToolbarActivity() {
    private lateinit var binding: ActivityRomSetupBinding
    private lateinit var romStore: RomStore

    private val pickRoms = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var imported = 0
        val unknown = ArrayList<String>()
        for (uri in uris) {
            val name = runCatching { romStore.importFrom(this, uri) }.getOrNull()
            if (name != null) imported++ else unknown += uri.lastPathSegment ?: "?"
        }
        if (imported > 0) Toast.makeText(this, getString(R.string.roms_imported, imported), Toast.LENGTH_SHORT).show()
        if (unknown.isNotEmpty()) Toast.makeText(this, getString(R.string.roms_unknown, unknown.joinToString()), Toast.LENGTH_LONG).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRomSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        romStore = RomStore(this)
        binding.buttonImport.setOnClickListener { pickRoms.launch(arrayOf("*/*")) }
        binding.buttonDone.setOnClickListener { finish() }
        refresh()
    }


    private fun refresh() {
        val ok = "✔"
        val missing = "✘"
        binding.status464.text = "${if (romStore.hasSystemRom(CpcModel.CPC464)) ok else missing}  cpc464.rom — ${CpcModel.CPC464.displayName}"
        binding.status664.text = "${if (romStore.hasSystemRom(CpcModel.CPC664)) ok else missing}  cpc664.rom — ${CpcModel.CPC664.displayName}"
        binding.status6128.text = "${if (romStore.hasSystemRom(CpcModel.CPC6128)) ok else missing}  cpc6128.rom — ${CpcModel.CPC6128.displayName}"
        binding.statusAmsdos.text = "${if (romStore.hasAmsdos()) ok else missing}  amsdos.rom — AMSDOS (${getString(R.string.roms_required_for_disc)})"
    }
}
