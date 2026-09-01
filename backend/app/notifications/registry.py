from app.notifications import ntfy, unifiedpush
from app.notifications.models import PushTransport
from app.notifications.transport import NotificationTransport


def get_transports() -> dict[PushTransport, NotificationTransport]:
    """Every transport this deployment can actually send with, keyed by the enum stored on a row.

    Its own module rather than a function in service.py or scheduler.py, and both alternatives
    were considered:

    - service.py is the DISPATCHER, and it is written against the `NotificationTransport`
      protocol precisely so it never names a concrete transport (6-B). Importing ntfy there to
      build a registry would undo that in one line.
    - scheduler.py is sync wiring. "Which configuration enables which transport" is
      notifications-domain policy, and it already lives inside each transport module's own
      `get_transport()`; the scheduler should ask, not decide.

    transport.py cannot host it either: ntfy.py and unifiedpush.py both import from transport.py,
    so the import would be circular.

    An empty mapping is a legitimate answer (6-K) and is what `start_scheduler` reads to decide
    not to register the dispatch job at all.
    """
    resolved = {
        PushTransport.NTFY: ntfy.get_transport(),
        PushTransport.UNIFIEDPUSH: unifiedpush.get_transport(),
    }
    return {name: transport for name, transport in resolved.items() if transport is not None}
