package flow.extension

/**
 * The names that have already been published, kept answering.
 *
 * A module is a jar on someone's machine, compiled once against whatever the contract was that day.
 * The JVM resolves what it names — the interface it implements, the class whose constructor it
 * calls — by name, at load time, and `META-INF/services` names that interface as plain text. So a
 * name here is not a word to be corrected; it is an address written down in every install out
 * there, and the only way to change one is to go on answering at the old one.
 *
 * Everything that could be renamed was. This is what could not.
 */

/**
 * What a module was called while the program's own word for one was "processor".
 *
 * Every jar built against that name is still out there, in installs nobody is going to rebuild, and
 * each one names this interface in its services file and in its class file. So it stays, as the
 * same contract under the older name: a class implementing it is a [ModuleExtension], and the host
 * asks ServiceLoader for both names. Nothing new should implement it.
 */
@Deprecated("Renamed to ModuleExtension", ReplaceWith("ModuleExtension"))
interface ProcessorExtension : ModuleExtension
