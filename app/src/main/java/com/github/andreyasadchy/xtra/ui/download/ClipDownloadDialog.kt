package com.github.andreyasadchy.xtra.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.helix.clip.Clip
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.android.synthetic.main.dialog_clip_download.*
import javax.inject.Inject

class ClipDownloadDialog : BaseDownloadDialog() {

    companion object {
        private const val KEY_QUALITIES = "urls"
        private const val KEY_CLIP = "clip"

        fun newInstance(clip: Clip, qualities: Map<String, String>? = null): ClipDownloadDialog {
            return ClipDownloadDialog().apply {
                arguments = bundleOf(KEY_CLIP to clip, KEY_QUALITIES to qualities)
            }
        }
    }

    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    private val viewModel by viewModels<ClipDownloadViewModel> { viewModelFactory }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?  =
            inflater.inflate(R.layout.dialog_clip_download, container, false)

    @Suppress("UNCHECKED_CAST")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        with(requireArguments()) {
            viewModel.init(requireContext().prefs().getString(C.GQL_CLIENT_ID, ""), getParcelable(KEY_CLIP)!!, getSerializable(KEY_QUALITIES) as Map<String, String>?)
        }
        viewModel.qualities.observe(viewLifecycleOwner, Observer {
            (requireView() as ConstraintLayout).children.forEach { v -> v.isVisible = v.id != R.id.progressBar && v.id != R.id.storageSelectionContainer }
            init(it)
        })
    }

    private fun init(qualities: Map<String, String>) {
        val context = requireContext()
        init(context)
        spinner.adapter = ArrayAdapter(context, R.layout.spinner_quality_item, qualities.keys.toTypedArray())
        cancel.setOnClickListener { dismiss() }
        download.setOnClickListener {
            val quality = spinner.selectedItem.toString()
            viewModel.download(qualities.getValue(quality), downloadPath, quality)
            dismiss()
        }
    }
}
