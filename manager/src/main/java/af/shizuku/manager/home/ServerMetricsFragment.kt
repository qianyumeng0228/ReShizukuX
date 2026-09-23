package af.shizuku.manager.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import af.shizuku.manager.R
import af.shizuku.manager.databinding.FragmentServerMetricsBinding
import timber.log.Timber

class ServerMetricsFragment : Fragment() {

    private var _binding: FragmentServerMetricsBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun updateStats() {
        // Server runtime metrics were previously sourced from the removed AICore+
        // diagnostic bridge. The UI views retain their default values until an
        // alternative metrics source is wired in.
    }

    private fun formatUptime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60)) % 24
        val days = (ms / (1000 * 60 * 60 * 24))
        return if (days > 0) String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds)
        else String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024
        val mb = kb / 1024
        return if (mb > 0) "$mb MB" else "$kb KB"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
