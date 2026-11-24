package com.example.rufusdata.health

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(Color.rgb(244, 244, 244))
        }

        val title = TextView(this).apply {
            text = "Uso dos dados de saúde"
            textSize = 24f
            setTextColor(Color.rgb(145, 179, 66))
            typeface = Typeface.DEFAULT_BOLD
        }

        val description = TextView(this).apply {
            text = """
                O RufusData solicita acesso de leitura à frequência cardíaca para recuperar as medições realizadas durante as sessões de pesquisa.

                Esses dados serão associados ao vídeo capturado pelo aplicativo, permitindo a análise conjunta das informações fisiológicas e faciais.

                O aplicativo não solicita acesso a outros tipos de dados de saúde nesta etapa.

                A leitura dos dados depende da autorização do usuário no Health Connect.
            """.trimIndent()

            textSize = 16f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 20f
            }
        }

        val button = TextView(this).apply {
            text = "Fechar"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 14, 20, 14)

            background = GradientDrawable().apply {
                setColor(Color.rgb(145, 179, 66))
                cornerRadius = 40f
            }

            setOnClickListener {
                finish()
            }
        }

        root.addView(title)
        root.addView(description)

        root.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
        )

        setContentView(root)
    }
}