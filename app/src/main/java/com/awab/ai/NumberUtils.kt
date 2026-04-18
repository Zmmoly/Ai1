package com.awab.ai

/**
 * =====================================================
 *  NumberUtils — تطبيع الأرقام العربية والإنجليزية
 * =====================================================
 *
 *  يحوّل أي نص يحتوي أرقاماً عربية (٠-٩) أو هندية-فارسية (۰-۹)
 *  إلى أرقام إنجليزية (0-9) قبل أي معالجة.
 *
 *  مثال:
 *    "ذكرني بعد ٥ دقائق"   →  "ذكرني بعد 5 دقائق"
 *    "الساعة ١٠:٣٠"         →  "الساعة 10:30"
 *    "كرر ٣ مرات"           →  "كرر 3 مرات"
 *    "اشتريت ٢ كيلو بـ١٥"   →  "اشتريت 2 كيلو بـ15"
 * =====================================================
 */
object NumberUtils {

    // جدول التحويل: العربية/الهندية → الإنجليزية
    private val ARABIC_DIGITS  = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
    private val PERSIAN_DIGITS = charArrayOf('۰','۱','۲','۳','۴','۵','۶','۷','۸','۹')

    /**
     * يحوّل الأرقام العربية والفارسية في النص إلى أرقام إنجليزية
     * يُستدعى على أي نص قبل تطبيق Regex أو toInt/toDouble
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val arabicIdx  = ARABIC_DIGITS.indexOf(ch)
            val persianIdx = PERSIAN_DIGITS.indexOf(ch)
            when {
                arabicIdx  >= 0 -> sb.append('0' + arabicIdx)
                persianIdx >= 0 -> sb.append('0' + persianIdx)
                else            -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

/** امتداد مريح — يُطبَّق على أي String مباشرة */
fun String.normalizeNumbers(): String = NumberUtils.normalize(this)

/**
 * تطبيع النص العربي:
 * - توحيد الهمزات: أ إ آ → ا
 * - توحيد التاء المربوطة: ة → ه
 * - إزالة التشكيل
 * - توحيد الألف المقصورة: ى → ي
 */
fun String.normalizeArabic(): String {
    return this
        // توحيد الهمزات → ا
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        // توحيد التاء المربوطة → ه
        .replace('ة', 'ه')
        // توحيد الألف المقصورة → ي
        .replace('ى', 'ي')
        // إزالة التشكيل
        .replace("ً", "").replace("ٌ", "")
        .replace("ٍ", "").replace("َ", "")
        .replace("ُ", "").replace("ِ", "")
        .replace("ّ", "").replace("ْ", "")
        .trim()
}

/** تطبيع شامل: أرقام + عربي */
fun String.normalizeAll(): String = this.normalizeNumbers().normalizeArabic()

