# Research Notes — Ledger Settlement

## 1. Maven Dependency Management

**Page title:** Introduction to the Dependency Mechanism

**Source:** https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism/

**Quoted sentence:** "Maven helps a great deal in defining, creating, and maintaining reproducible builds with well-defined classpaths and library versions."

**How it affected my project:** I added JUnit Jupiter and AssertJ to pom.xml with explicit version numbers so the project uses pinned test dependencies.

## 2. JUnit Assertions

**Page title:** JUnit User Guide — Assertions

**Source:** https://docs.junit.org/current/user-guide/

**Quoted sentence:** "All JUnit Jupiter assertions are static methods in the org.junit.jupiter.api.Assertions class."

**How it affected my project:** I used JUnit assertions in SettlementServiceTest to verify the settlement calculation, empty-list behaviour, and other required cases. I also used AssertJ for the exception assertion.

## 3. Pinned Dependencies

- JUnit Jupiter: 5.14.3
- AssertJ: 3.27.7

These versions are recorded in pom.xml and README.md.

## 4. References

- Maven Guides: https://maven.apache.org/guides/
- JUnit User Guide: https://docs.junit.org/current/user-guide/
- AssertJ Documentation: https://assertj.github.io/doc/
- Abraham Oluremi
- Chukwuma Akunyili

# Research

## Maven Dependency Management

Page title: Introduction to the Dependency Mechanism

> You can always guarantee a version by declaring it explicitly in your project's POM.

Source: https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism

## JUnit 5 Assertions

Page title: JUnit 5 User Guide — Assertions

> Assertions are static methods in the org.junit.jupiter.api.Assertions class.

Source: https://junit.org/junit5/docs/5.11.1/user-guide/index.html

## What changed in my project

I pinned explicit dependency versions in pom.xml and used JUnit assertions in my tests.