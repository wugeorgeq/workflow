package main

import (
	"fmt"
	"io/ioutil"
	"os"
	"strings"
	"time"
)

// Updates CHANGELOG.md by adding new CHANGELOGs from
// the environment variables Swift ($SWIFT_CHANGELOG)
// & Kotlin ($KOTLIN_CHANGELOG)
func main() {
	dat, _ := ioutil.ReadFile("CHANGELOG.md")
	str := string(dat)
	comps := strings.Split(str, "\n")

	res := comps[:2]
	res = append(res, getNewChangelogSection())
	res = append(res, comps[3:]...)

	ioutil.WriteFile("CHANGELOG.md", []byte(strings.Join(res, "\n")), 0644)
}

func getNewChangelogSection() string {
	return fmt.Sprintf(
		`
## Version %v

%v

### Kotlin

%v

### Swift

%v
`,
		os.Getenv("WORKFLOW_VERSION"),
		time.Now().Format("_2006-01-02_"),
		strings.TrimSpace(os.Getenv("KOTLIN_CHANGELOG")),
		strings.TrimSpace(os.Getenv("SWIFT_CHANGELOG")),
	)
}
