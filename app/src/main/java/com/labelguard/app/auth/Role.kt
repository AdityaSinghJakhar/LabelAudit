package com.labelguard.app.auth

/**
 * Who is using the app.
 *
 * This is not only a menu of buttons. A role decides what a person's
 * assertions are worth to everyone else, which is the part that matters: the
 * registry's whole weakness is that it believes whoever filled it in. A
 * shopper photographing a packet they just bought has no way of knowing it is
 * genuine, so nothing they enrol may ever become the reference another scan
 * is failed against.
 *
 * Neither role is a security boundary. Everything here runs on one phone with
 * one person holding it, and a local gate keeps an honest user in their lane
 * rather than keeping a determined one out. Real authentication needs the
 * server that the reference-sync design would bring, and [Role] is what that
 * server would authenticate people *into*.
 */
enum class Role {

    /** Anyone with the app. The default, and the larger audience. */
    CONSUMER,

    /** An enforcement official acting in the course of inspection. */
    INSPECTOR;

    val label: String
        get() = when (this) {
            CONSUMER -> "Shopper"
            INSPECTOR -> "Inspector"
        }

    val summary: String
        get() = when (this) {
            CONSUMER ->
                "Scan packs, read the findings and save reports. Your scans " +
                    "stay on this phone."
            INSPECTOR ->
                "Everything a shopper can do, plus registering reference " +
                    "packs and exporting the inspection history."
        }

    fun can(capability: Capability): Boolean = capability in capabilities

    val capabilities: Set<Capability>
        get() = when (this) {
            CONSUMER -> setOf(
                Capability.SCAN,
                Capability.VIEW_REPORT,
                Capability.EXPORT_REPORT,
                Capability.VIEW_OWN_HISTORY
            )

            INSPECTOR -> setOf(
                Capability.SCAN,
                Capability.VIEW_REPORT,
                Capability.EXPORT_REPORT,
                Capability.VIEW_OWN_HISTORY,
                Capability.ENROL_REFERENCE,
                Capability.MANAGE_REGISTRY,
                Capability.EXPORT_HISTORY,
                Capability.CLEAR_HISTORY
            )
        }

    enum class Capability {
        SCAN,
        VIEW_REPORT,

        /** Save or share the PDF for a single scan. */
        EXPORT_REPORT,

        VIEW_OWN_HISTORY,

        /**
         * Register a scanned pack as the reference other packs are compared
         * against.
         *
         * Withheld from shoppers deliberately. A reference asserts "this is
         * what a correct pack of this product says", and someone who bought a
         * packet off a shelf cannot know that — including, in the worst case,
         * a relabeller enrolling their own repasted pack so every later fake
         * of it passes.
         */
        ENROL_REFERENCE,

        MANAGE_REGISTRY,

        /**
         * Export the whole inspection history. A shopper can export any
         * individual report; the aggregate is an enforcement artefact.
         */
        EXPORT_HISTORY,

        CLEAR_HISTORY
    }
}
