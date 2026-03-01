package pl.kacosmetology.scheduler.whatsapp

/** Represents the current step in the booking conversation flow. */
enum class ConversationStep {
    /** No active conversation; next message starts a new booking flow. */
    IDLE,
    /** Waiting for the client to pick a service from the numbered list. */
    SELECTING_SERVICE,
    /** Waiting for the client to pick an employee from the numbered list. */
    SELECTING_EMPLOYEE,
    /** Waiting for the client to pick a date from the numbered list. */
    SELECTING_DATE,
    /** Waiting for the client to pick a time slot from the numbered list. */
    SELECTING_TIME,
    /** Waiting for "tak" / "nie" to confirm or cancel the booking summary. */
    CONFIRMING,
    /** New client flow: waiting for the client to type their first name. */
    AWAITING_FIRST_NAME,
    /** New client flow: waiting for the client to type their last name. */
    AWAITING_LAST_NAME
}
