package com.awab.ai

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class CustomCommandsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private val PRIMARY_COLOR = 0xFF075E54.toInt()
    private val BG_COLOR = 0xFFF0F2F5.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val RED = 0xFFDC3545.toInt()
    private val ORANGE = 0xFFFF8C00.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildUI()
    }

    // ===== بناء الواجهة الرئيسية =====

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_COLOR)
            fitsSystemWindows = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // شريط العنوان
        root.addView(buildHeader())

        // زر إضافة جديد
        root.addView(buildAddButton())

        // قائمة الأوامر المخصصة
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        setContentView(root)
        refreshList()
    }

    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(PRIMARY_COLOR)
            setPadding(16, 20, 16, 20)
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(this@CustomCommandsActivity).apply {
                text = "←"
                textSize = 22f
                setTextColor(WHITE)
                setPadding(0, 0, 16, 0)
                setOnClickListener { finish() }
            })

            addView(TextView(this@CustomCommandsActivity).apply {
                text = "⚡ الأوامر المخصصة"
                textSize = 20f
                setTextColor(WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun buildAddButton(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(WHITE)
            setPadding(16, 12, 16, 12)

            addView(TextView(this@CustomCommandsActivity).apply {
                text = "+ إضافة أمر مخصص جديد"
                textSize = 16f
                setTextColor(PRIMARY_COLOR)
                setPadding(24, 14, 24, 14)
                background = roundedBorder(PRIMARY_COLOR, 28f)
                setOnClickListener { showAddEditDialog(null) }
            })
        }
    }

    // ===== تحديث القائمة =====

    private fun refreshList() {
        listContainer.removeAllViews()
        val commands = CustomCommandsManager.loadCommands(this)

        if (commands.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "لا توجد أوامر مخصصة بعد.\nاضغط على \"+ إضافة\" لإنشاء أول أمر!"
                textSize = 15f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                setPadding(32, 64, 32, 64)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            return
        }

        for (cmd in commands) {
            listContainer.addView(buildCommandCard(cmd))
            listContainer.addView(View(this).apply {
                setBackgroundColor(0xFFE0E0E0.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, 4, 0, 4) }
            })
        }
    }

    private fun buildCommandCard(cmd: CustomCommand): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedCard(WHITE, 12f)
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }

            // صف العنوان والأزرار
            addView(LinearLayout(this@CustomCommandsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                // اسم الأمر
                addView(LinearLayout(this@CustomCommandsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                    addView(TextView(this@CustomCommandsActivity).apply {
                        text = "⚡ ${cmd.name}"
                        textSize = 17f
                        setTextColor(0xFF1A1A1A.toInt())
                    })
                    if (cmd.description.isNotBlank()) {
                        addView(TextView(this@CustomCommandsActivity).apply {
                            text = cmd.description
                            textSize = 13f
                            setTextColor(0xFF888888.toInt())
                        })
                    }
                    addView(TextView(this@CustomCommandsActivity).apply {
                        text = "${cmd.steps.size} خطوات • تأخير ${cmd.delaySeconds}ث"
                        textSize = 12f
                        setTextColor(0xFF888888.toInt())
                        setPadding(0, 4, 0, 0)
                    })
                })

                // زر تشغيل
                addView(TextView(this@CustomCommandsActivity).apply {
                    text = "▶"
                    textSize = 18f
                    setTextColor(WHITE)
                    setPadding(14, 8, 14, 8)
                    background = roundedCard(PRIMARY_COLOR, 10f)
                    setOnClickListener { runCustomCommand(cmd) }
                })

                // زر تعديل
                addView(TextView(this@CustomCommandsActivity).apply {
                    text = "✏"
                    textSize = 18f
                    setTextColor(WHITE)
                    setPadding(14, 8, 14, 8)
                    background = roundedCard(ORANGE, 10f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(8, 0, 0, 0) }
                    setOnClickListener { showAddEditDialog(cmd) }
                })

                // زر حذف
                addView(TextView(this@CustomCommandsActivity).apply {
                    text = "🗑"
                    textSize = 18f
                    setTextColor(WHITE)
                    setPadding(14, 8, 14, 8)
                    background = roundedCard(RED, 10f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(8, 0, 0, 0) }
                    setOnClickListener { confirmDelete(cmd) }
                })
            })

            // معاينة الخطوات
            addView(View(this@CustomCommandsActivity).apply {
                setBackgroundColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, 12, 0, 8) }
            })

            cmd.steps.forEachIndexed { i, raw ->
                val step = StepEngine.parse(raw)
                val color = when (step) {
                    is Step.Normal   -> 0xFF444444.toInt()
                    is Step.IfChain  -> 0xFF1565C0.toInt()
                    is Step.Loop     -> 0xFF6A1B9A.toInt()
                    is Step.Wait     -> 0xFF00796B.toInt()
                    is Step.Delay    -> 0xFFE65100.toInt()
                    is Step.ItemList -> if (step.isUrl) 0xFF0277BD.toInt() else 0xFF00897B.toInt()
                }
                addView(TextView(this@CustomCommandsActivity).apply {
                    text = "${i + 1}. ${StepEngine.describe(step)}"
                    textSize = 13f
                    setTextColor(color)
                    setPadding(0, 3, 0, 3)
                })
            }
        }
    }

    // ===== ديالوج إضافة / تعديل =====

    private fun showAddEditDialog(existing: CustomCommand?) {
        val isEdit = existing != null
        val dialogView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        dialogView.addView(container)

        // حقل الاسم
        container.addView(label("اسم الأمر (سيكتبه المستخدم لتشغيله):"))
        val nameField = editField("مثال: روتين الصباح").apply {
            setText(existing?.name ?: "")
        }
        container.addView(nameField)

        // حقل الوصف
        container.addView(label("وصف الأمر (اختياري):"))
        val descField = editField("وصف مختصر للأمر").apply {
            setText(existing?.description ?: "")
        }
        container.addView(descField)

        // حقل التأخير
        container.addView(label("التأخير بين الخطوات (بالثواني):"))
        val delayField = editField("2").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((existing?.delaySeconds ?: 2).toString())
        }
        container.addView(delayField)

        // قسم الخطوات
        container.addView(label("الخطوات بالتسلسل:"))

        val stepsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(stepsContainer)

        // تعبئة الخطوات الموجودة
        val stepFields = mutableListOf<EditText>()
        if (existing != null) {
            for (step in existing.steps) {
                val field = addStepRow(stepsContainer, stepFields, step)
                stepFields.add(field)
            }
        } else {
            // خطوتان فارغتان للبدء
            stepFields.add(addStepRow(stepsContainer, stepFields, ""))
            stepFields.add(addStepRow(stepsContainer, stepFields, ""))
        }

        // أزرار إضافة الخطوات
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 4)
        }

        fun makeAddBtn(label: String, color: Int, template: String) = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(WHITE)
            setPadding(14, 8, 14, 8)
            background = roundedCard(color, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 8, 0) }
            setOnClickListener {
                stepFields.add(addStepRow(stepsContainer, stepFields, template))
            }
        }

        btnRow.addView(makeAddBtn("+ خطوة", PRIMARY_COLOR, ""))
        btnRow.addView(makeAddBtn("🔀 شرط", 0xFF1565C0.toInt(),
            "إذا الشاشة تحتوي [نص]: [أمر_صح] وإلا: [أمر_خطأ]"))
        btnRow.addView(makeAddBtn("🔁 حلقة سطر", 0xFF6A1B9A.toInt(),
            "كرر [N] مرات: [الأمر]"))
        btnRow.addView(makeAddBtn("🔁 حلقة متعددة", 0xFF4A148C.toInt(),
            "ابدأ حلقة [N] مرات"))
        btnRow.addView(makeAddBtn("🔚 انهي حلقة", 0xFF4A148C.toInt(),
            "انهي حلقة"))
        btnRow.addView(makeAddBtn("⏳ انتظار", 0xFF00796B.toInt(),
            "انتظر ظهور [نص] لمدة 15 ثانية ثم اضغط على [نص]"))
        container.addView(btnRow)

        // صف أزرار القوائم
        val listBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        listBtnRow.addView(makeAddBtn("📋 قائمة نصوص", 0xFF00897B.toInt(),
            "قائمة نصوص: [نص1] | [نص2] | [نص3]"))
        listBtnRow.addView(makeAddBtn("🔗 قائمة روابط", 0xFF0277BD.toInt(),
            "قائمة روابط: [https://رابط1] | [https://رابط2]"))
        container.addView(listBtnRow)

        // تلميح الصيغ
        container.addView(label("💡 صيغ الخطوات:"))
        container.addView(TextView(this).apply {
            text = StepEngine.SYNTAX_HINTS
            textSize = 12f
            setTextColor(0xFF444444.toInt())
            setBackgroundColor(0xFFF0F4FF.toInt())
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(0xFFF0F4FF.toInt())
                cornerRadius = 8f
            }
        })

        // تلميح الأوامر المتاحة
        container.addView(label("📋 الأوامر المتاحة:"))
        container.addView(TextView(this).apply {
            text = CustomCommandsManager.AVAILABLE_COMMANDS.joinToString("\n") { "• $it" }
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setBackgroundColor(0xFFF8F8F8.toInt())
            setPadding(16, 12, 16, 12)
        })

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "✏️ تعديل الأمر" else "➕ إضافة أمر مخصص")
            .setView(dialogView)
            .setPositiveButton(if (isEdit) "حفظ التعديلات" else "إضافة") { _, _ ->
                val name = nameField.text.toString().trim()
                val desc = descField.text.toString().trim()
                val delay = delayField.text.toString().toIntOrNull() ?: 2
                val steps = stepFields
                    .map { it.text.toString().trim() }
                    .filter { it.isNotEmpty() }

                if (name.isEmpty()) {
                    Toast.makeText(this, "⚠️ يجب إدخال اسم الأمر", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (steps.isEmpty()) {
                    Toast.makeText(this, "⚠️ يجب إضافة خطوة واحدة على الأقل", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val command = CustomCommand(
                    id = existing?.id ?: CustomCommandsManager.generateId(),
                    name = name,
                    description = desc,
                    steps = steps,
                    delaySeconds = delay.coerceIn(1, 30)
                )

                if (isEdit) {
                    CustomCommandsManager.updateCommand(this, command)
                    Toast.makeText(this, "✅ تم تحديث الأمر", Toast.LENGTH_SHORT).show()
                } else {
                    CustomCommandsManager.addCommand(this, command)
                    Toast.makeText(this, "✅ تم إضافة الأمر", Toast.LENGTH_SHORT).show()
                }
                refreshList()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun addStepRow(
        container: LinearLayout,
        stepFields: MutableList<EditText>,
        initialText: String
    ): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 6, 0, 0) }
        }

        val stepNum = TextView(this).apply {
            text = "${stepFields.size + 1}."
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 8, 0)
            minWidth = 32
        }

        val field = EditText(this).apply {
            hint = "أدخل الأمر هنا..."
            textSize = 14f
            setPadding(12, 10, 12, 10)
            setText(initialText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = GradientDrawable().apply {
                setColor(0xFFF0F0F0.toInt())
                cornerRadius = 8f
            }
        }

        val deleteBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(RED)
            setPadding(12, 0, 0, 0)
            setOnClickListener {
                container.removeView(row)
                stepFields.remove(field)
                // تحديث أرقام الخطوات
                refreshStepNumbers(container)
            }
        }

        row.addView(stepNum)
        row.addView(field)
        row.addView(deleteBtn)
        container.addView(row)
        return field
    }

    private fun refreshStepNumbers(container: LinearLayout) {
        var count = 1
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            val numView = row.getChildAt(0) as? TextView ?: continue
            numView.text = "$count."
            count++
        }
    }

    // ===== تأكيد الحذف =====

    private fun confirmDelete(cmd: CustomCommand) {
        AlertDialog.Builder(this)
            .setTitle("🗑️ حذف الأمر")
            .setMessage("هل تريد حذف الأمر \"${cmd.name}\"؟\nلا يمكن التراجع عن هذا.")
            .setPositiveButton("حذف") { _, _ ->
                CustomCommandsManager.deleteCommand(this, cmd.id)
                Toast.makeText(this, "✅ تم حذف الأمر", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ===== تشغيل الأمر المخصص (معاينة) =====

    private fun runCustomCommand(cmd: CustomCommand) {
        val preview = StringBuilder()
        preview.appendLine("⚡ سيتم تنفيذ: \"${cmd.name}\"")
        preview.appendLine()
        cmd.steps.forEachIndexed { i, raw ->
            val step = StepEngine.parse(raw)
            preview.appendLine("${i + 1}. ${StepEngine.describe(step)}")
            if (i < cmd.steps.size - 1) preview.appendLine("   ⏳ انتظار ${cmd.delaySeconds}ث...")
        }

        AlertDialog.Builder(this)
            .setTitle("▶️ تشغيل الأمر")
            .setMessage(preview.toString())
            .setPositiveButton("تشغيل الآن") { _, _ ->
                Toast.makeText(this, "⚡ اكتب \"${cmd.name}\" في شاشة الرئيسية لتشغيله", Toast.LENGTH_LONG).show()
                val result = android.content.Intent()
                result.putExtra("run_custom_command", cmd.name)
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    // ===== مساعدات UI =====

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF555555.toInt())
        setPadding(0, 16, 0, 4)
    }

    private fun editField(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setPadding(16, 12, 16, 12)
        background = GradientDrawable().apply {
            setColor(0xFFF5F5F5.toInt())
            cornerRadius = 10f
            setStroke(1, 0xFFCCCCCC.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun roundedCard(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun roundedBorder(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = radius
        setStroke(2, color)
    }
}
