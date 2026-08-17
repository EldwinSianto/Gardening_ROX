package com.roxgarden

import android.content.Context

/**
 * Katalog STATIS tanaman gardening + persistensi pilihan user (multi-plant mode).
 *
 * Cara nambah tanaman:
 *   1. Crop thumbnail encyclopedia-nya → app/src/main/assets/plants/<id>.png
 *   2. Kalau kotanya belum ada di [WeatherScanner.CITY_ASSETS], crop ikon world-map kota itu
 *      → app/src/main/assets/map_icons/<kota>.png dan daftarkan di WeatherScanner + CITY_ORDER.
 *   3. Tambah satu baris PlantDef di [PlantCatalog.ALL].
 *
 * PENTING: nama kota di `cities` HARUS sama persis dengan key WeatherScanner.CITY_ASSETS
 * dan ada di CITY_ORDER (dipakai utk sweep utara→selatan & template ikon world-map).
 */
data class PlantDef(
    val id: String,            // "cactus"
    val displayName: String,   // "Cactus"
    val iconAsset: String,     // "plants/cactus.png" (thumbnail encyclopedia)
    val cities: List<String>   // ["Morroc", "Sograt Desert"]
)

object PlantCatalog {
    val ALL: List<PlantDef> = listOf(
        PlantDef("cactus", "Cactus", "plants/cactus.png", listOf("Morroc", "Sograt Desert")),
        PlantDef("dark_olive", "Dark Olive", "plants/dark_olive.png", listOf("Glast Heim")),
        // tambah tanaman lain di sini setelah crop ikonnya
    )

    // Urutan kota utara→selatan — biar sweep WeatherScanner pendek (tiap kota cuma sedikit di bawah)
    val CITY_ORDER: List<String> = listOf("Glast Heim", "Sograt Desert", "Morroc")

    fun byId(id: String): PlantDef? = ALL.firstOrNull { it.id == id }
}

/**
 * Persistensi pilihan tanaman (terurut = prioritas tinggi→rendah).
 * Disimpan di SharedPreferences "roxgarden_prefs", key "selected_plants" = JSON array of id.
 * Dipakai bareng MainActivity (tulis) & GardenService (baca).
 */
object PlantConfig {
    private const val PREFS = "roxgarden_prefs"
    private const val KEY = "selected_plants"

    /** Daftar id terurut prioritas. Kosong = belum ada pilihan. */
    fun getSelectedIds(ctx: Context): List<String> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
                .filter { PlantCatalog.byId(it) != null }   // buang id yang sudah tak ada di katalog
        } catch (_: Throwable) { emptyList() }
    }

    /** PlantDef terurut prioritas. */
    fun getSelected(ctx: Context): List<PlantDef> =
        getSelectedIds(ctx).mapNotNull { PlantCatalog.byId(it) }

    fun setSelectedIds(ctx: Context, ids: List<String>) {
        val arr = org.json.JSONArray()
        ids.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** Union kota dari semua tanaman terpilih, diurutkan sesuai CITY_ORDER (utara→selatan). */
    fun citiesFor(selected: List<PlantDef>): List<String> {
        val set = selected.flatMap { it.cities }.toSet()
        val ordered = PlantCatalog.CITY_ORDER.filter { it in set }
        // kota yang belum ada di CITY_ORDER (lupa didaftarkan) tetap disertakan di akhir
        val rest = set.filter { it !in PlantCatalog.CITY_ORDER }
        return ordered + rest
    }
}
