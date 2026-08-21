from pydantic import BaseModel


class PrefsRead(BaseModel):
    push_enabled: bool


class PrefsUpdate(BaseModel):
    # Required, not optional-with-exclude_unset. Unlike PATCH /v1/library/{id}, which is a genuine
    # partial update over several fields, this resource has exactly one field — so "unset" and
    # "no-op" would be the same request, and accepting an empty body would silently do nothing.
    push_enabled: bool
