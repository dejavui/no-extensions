package eu.kanade.tachiyomi.extension.vi.hentaivnbond

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.annotation.Source
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class HentaiVNBond :
    Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
    override val mangaSubString = "truyenhentai"

}
