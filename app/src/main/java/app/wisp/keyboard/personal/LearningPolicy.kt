package app.wisp.keyboard.personal

import app.wisp.keyboard.kbd.FieldInfo

/**
 * Whether anything typed in the current field may be learned. The answer is no for password fields, fields that ask
 * for no personalised learning, while incognito is on, and in apps the user has excluded.
 */
object LearningPolicy {
    fun allowed(field: FieldInfo, incognito: Boolean, packageName: String?, excludedPackages: Set<String>): Boolean =
        field.learn && !incognito && (packageName == null || packageName !in excludedPackages)
}
