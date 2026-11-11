"""Restrict official-source redirects before making the follow-up request."""
import urllib.request


class RestrictedRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, allowed):
        self.allowed = allowed

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not self.allowed(newurl):
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)
