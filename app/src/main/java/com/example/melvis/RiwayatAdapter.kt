import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.melvis.R
import com.example.melvis.RiwayatItem

class RiwayatAdapter(
    private val list: MutableList<RiwayatItem>,
    private val onDeleteClick: (item: RiwayatItem, position: Int) -> Unit
) : RecyclerView.Adapter<RiwayatAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgFoto: ImageView = itemView.findViewById(R.id.imgFoto)
        val tvLabel: TextView = itemView.findViewById(R.id.tvLabel)
        val tvWaktu: TextView = itemView.findViewById(R.id.tvWaktu)
        val tvSuhu: TextView = itemView.findViewById(R.id.tvSuhu)
        val tvKelembapan: TextView = itemView.findViewById(R.id.tvKelembapan)
        val btnHapus: ImageView = itemView.findViewById(R.id.btnHapus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_riwayat, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.tvLabel.text = item.label
        holder.tvWaktu.text = "🕒 ${item.waktu}"
        holder.tvSuhu.text = "🌡️ ${item.suhu} °C"
        holder.tvKelembapan.text = "💧 ${item.kelembapan}%"

        Glide.with(holder.itemView.context)
            .load(item.image_url)
            .placeholder(R.drawable.icon_home)
            .into(holder.imgFoto)

        // Tombol hapus
        holder.btnHapus.setOnClickListener {
            onDeleteClick(item, position)
        }
    }

    // Hapus item dari adapter
    fun removeItem(position: Int) {
        list.removeAt(position)
        notifyItemRemoved(position)
    }
}
