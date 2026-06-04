package version

var (
	Version   = "dev"
	Commit    = "none"
	BuildTime = "unknown"
)

func String() string {
	return Version + " (commit: " + Commit + ", built at: " + BuildTime + ")"
}
