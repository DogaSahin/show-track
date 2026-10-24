import re

# Everything the server knows about the shape of a TMDB poster URL lives here, inside the
# providers package, so the relay route and the schemas that rewrite cover URLs name no TMDB
# types — the same boundary MediaProvider keeps for data.
TMDB_IMAGE_PREFIX = "https://image.tmdb.org/t/p/"
RELAY_PREFIX = "/v1/images/tmdb/"

# Only the size the TMDB mapper emits. A wider set would let a caller make this server fetch
# `original` files (several MB each) for no screen that asks for them.
RELAY_SIZES = frozenset({"w500"})

# TMDB file names are content-addressed alphanumerics. Matching the whole name against this, and
# never splicing caller input into a URL any other way, is what keeps the relay from being an open
# proxy: no `/`, `.`-segments, `?`, `#` or `%` can reach the upstream URL.
FILE_NAME_PATTERN = re.compile(r"^[A-Za-z0-9]{1,64}\.(jpg|png)$")


def tmdb_upstream_url(size: str, file_name: str) -> str | None:
    """The TMDB URL for a relay request, or None when either part is not one we serve."""
    # fullmatch, not the pattern's own `$`: `$` also matches before a trailing newline.
    if size not in RELAY_SIZES or FILE_NAME_PATTERN.fullmatch(file_name) is None:
        return None
    return f"{TMDB_IMAGE_PREFIX}{size}/{file_name}"
