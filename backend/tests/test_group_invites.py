import pytest

from app.groups import invites
from app.groups.invites import ALPHABET, CODE_LENGTH, generate_code, normalise_code


def test_a_generated_code_is_the_specified_length_and_alphabet():
    code = generate_code()

    assert len(code) == CODE_LENGTH
    assert set(code) <= set(ALPHABET)


def test_the_alphabet_excludes_every_confusable_letter():
    """Crockford base32. The exclusion is what makes folding O->0 and I/L->1 unambiguous:
    a typed O can only ever have meant 0, because the generator cannot emit O."""
    assert set("ILOU").isdisjoint(ALPHABET)
    assert len(set(ALPHABET)) == 32


def test_generated_codes_differ():
    """Weak by design, and weaker than it looks: it catches a CONSTANT generator — the failure
    that would make every group share one code — and nothing beyond that. A seeded
    `random.Random(42).choice` substituted for `secrets.choice` passes this and every other
    test in this module, because distinctness is not unpredictability. The test below is what
    covers the credential property."""
    assert len({generate_code() for _ in range(200)}) == 200


def test_the_code_is_drawn_from_secrets_not_random(monkeypatch):
    """An invite code creates an account (Phase 7.5a) with no rate limiting anywhere in front of
    it, so the draw being unpredictable is a security property, not an implementation detail.

    Unpredictability cannot be asserted from outputs — any PRNG produces distinct, uniform,
    correctly-shaped codes — so this asserts the SOURCE instead: every character comes through
    `secrets.choice`. A spy rather than a stub, so the generator's real output is still returned
    and the module's other guarantees are unaffected."""
    drawn: list[str] = []
    real_choice = invites.secrets.choice

    def spy(sequence):
        drawn.append(sequence)
        return real_choice(sequence)

    monkeypatch.setattr(invites.secrets, "choice", spy)

    code = generate_code()

    assert drawn == [ALPHABET] * CODE_LENGTH
    assert len(code) == CODE_LENGTH


@pytest.mark.parametrize(
    "typed",
    ["abcdefgh2345", "ABCD-EFGH-2345", " ABCDEFGH2345 ", "abcd efgh 2345", "ABCD_EFGH_2345"],
)
def test_normalisation_accepts_how_a_human_retypes_a_code(typed):
    assert normalise_code(typed) == "ABCDEFGH2345"


def test_normalisation_folds_confusable_letters_onto_their_digits():
    assert normalise_code("OIL") == "011"


def test_normalising_a_generated_code_is_a_no_op():
    """The property that matters: the write path stores what the read path will look for.
    If this ever fails, every code in the database is unreachable and the symptom looks
    like "the user typed it wrong"."""
    code = generate_code()

    assert normalise_code(code) == code
