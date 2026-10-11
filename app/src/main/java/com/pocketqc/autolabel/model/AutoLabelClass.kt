package com.pocketqc.autolabel.model

/**
 * Experimental dataset class vocabulary for biscuit auto-labeling PoC.
 *
 * NOTE: Independent from the production model class mapping:
 * - Experimental Dataset: 0 = Intact, 1 = Fracture, 2 = Broken
 * - Production Model:     0 = Broken, 1 = Fracture, 2 = Intact
 *
 * This mapping decoupling is strictly preserved by this enum.
 */
enum class AutoLabelClass(val classId: Int, val displayName: String) {
    INTACT(0, "Intact"),
    FRACTURE(1, "Fracture"),
    BROKEN(2, "Broken");

    companion object {
        fun fromId(id: Int): AutoLabelClass? {
            return entries.firstOrNull { it.classId == id }
        }

        fun fromName(name: String): AutoLabelClass? {
            return entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
        }

        /**
         * Translates production model detection label strings to experimental dataset class.
         * Production labels: "Intact", "Fracture", "Broken"
         */
        fun fromProductionLabel(productionLabel: String): AutoLabelClass {
            return when (productionLabel.trim().lowercase()) {
                "intact" -> INTACT
                "fracture" -> FRACTURE
                "broken" -> BROKEN
                else -> BROKEN
            }
        }

        /**
         * Translates production model class ID (0=Broken, 1=Fracture, 2=Intact)
         * to experimental class ID (0=Intact, 1=Fracture, 2=Broken).
         */
        fun fromProductionClassId(prodId: Int): AutoLabelClass {
            return when (prodId) {
                0 -> BROKEN
                1 -> FRACTURE
                2 -> INTACT
                else -> BROKEN
            }
        }
    }
}
