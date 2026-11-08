package jadx.plugins.emu.exec

/**
 * Sentinel returned by stub and host lookups meaning "no implementation here, try the next layer".
 * Compare by identity.
 */
internal object NotHandled

/**
 * Thrown when emulated code calls a framework method that has a signature stub but no behaviour.
 *
 * @property descriptor the method, as `Lcls;->shortId`
 */
internal class StubNotImplemented(internal val descriptor: String) :
    RuntimeException("no implementation for framework method $descriptor; register one with Hooks.stub")
